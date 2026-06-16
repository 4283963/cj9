package postgresclient

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"time"

	"github.com/jackc/pgx/v4"
	"github.com/jackc/pgx/v4/pgxpool"
	"archive-service/config"
	redisclient "archive-service/internal/redis"
)

type Client struct {
	pool    *pgxpool.Pool
	batchSize int
	ctx     context.Context
}

type ArchivedGameRecord struct {
	ID             int64     `db:"id"`
	GameID         string    `db:"game_id"`
	PlayerID       string    `db:"player_id"`
	Score          int       `db:"score"`
	Stage          int       `db:"stage"`
	EnemiesKilled  int       `db:"enemies_killed"`
	GameTime       float32   `db:"game_time"`
	StartTime      time.Time `db:"start_time"`
	SubmitTime     time.Time `db:"submit_time"`
	FrameCount     int       `db:"frame_count"`
	InputData      []byte    `db:"input_data"`
	InputDataSize  int       `db:"input_data_size"`
	Compressed     bool      `db:"compressed"`
	Verified       bool      `db:"verified"`
	Checksum       string    `db:"checksum"`
	CreatedAt      time.Time `db:"created_at"`
}

func NewClient(cfg *config.Config) (*Client, error) {
	connStr := fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=disable",
		cfg.PostgresUser,
		cfg.PostgresPassword,
		cfg.PostgresHost,
		cfg.PostgresPort,
		cfg.PostgresDB,
	)

	poolConfig, err := pgxpool.ParseConfig(connStr)
	if err != nil {
		return nil, fmt.Errorf("failed to parse connection config: %w", err)
	}

	poolConfig.MaxConns = 10
	poolConfig.MinConns = 2

	pool, err := pgxpool.ConnectConfig(context.Background(), poolConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to postgres: %w", err)
	}

	client := &Client{
		pool:      pool,
		batchSize: cfg.BatchSize,
		ctx:       context.Background(),
	}

	if err := client.InitSchema(); err != nil {
		return nil, fmt.Errorf("failed to init schema: %w", err)
	}

	return client, nil
}

func (c *Client) InitSchema() error {
	schema := `
		CREATE TABLE IF NOT EXISTS game_records_archive (
			id BIGSERIAL PRIMARY KEY,
			game_id VARCHAR(64) NOT NULL UNIQUE,
			player_id VARCHAR(64) NOT NULL,
			score INTEGER NOT NULL,
			stage INTEGER NOT NULL,
			enemies_killed INTEGER NOT NULL,
			game_time REAL NOT NULL,
			start_time TIMESTAMP NOT NULL,
			submit_time TIMESTAMP NOT NULL,
			frame_count INTEGER NOT NULL,
			input_data BYTEA NOT NULL,
			input_data_size INTEGER NOT NULL,
			compressed BOOLEAN DEFAULT TRUE,
			verified BOOLEAN DEFAULT FALSE,
			checksum VARCHAR(32),
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		);

		CREATE INDEX IF NOT EXISTS idx_game_records_archive_game_id ON game_records_archive(game_id);
		CREATE INDEX IF NOT EXISTS idx_game_records_archive_player_id ON game_records_archive(player_id);
		CREATE INDEX IF NOT EXISTS idx_game_records_archive_score ON game_records_archive(score DESC);
		CREATE INDEX IF NOT EXISTS idx_game_records_archive_submit_time ON game_records_archive(submit_time DESC);
		CREATE INDEX IF NOT EXISTS idx_game_records_archive_created_at ON game_records_archive(created_at);

		CREATE TABLE IF NOT EXISTS archive_summary (
			id SERIAL PRIMARY KEY,
			archive_date DATE NOT NULL UNIQUE,
			total_records INTEGER NOT NULL DEFAULT 0,
			total_score BIGINT NOT NULL DEFAULT 0,
			average_score REAL NOT NULL DEFAULT 0,
			compressed_total_size BIGINT NOT NULL DEFAULT 0,
			original_total_size BIGINT NOT NULL DEFAULT 0,
			compression_ratio REAL NOT NULL DEFAULT 0,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		);
	`

	_, err := c.pool.Exec(c.ctx, schema)
	return err
}

func (c *Client) ArchiveRecords(records []redisclient.GameRecord) (int, error) {
	if len(records) == 0 {
		return 0, nil
	}

	tx, err := c.pool.Begin(c.ctx)
	if err != nil {
		return 0, fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback(c.ctx)

	batch := &pgx.Batch{}
	totalOriginalSize := 0
	totalCompressedSize := 0
	totalScore := int64(0)
	insertCount := 0

	for _, record := range records {
		inputJSON, err := json.Marshal(record.InputSequence)
		if err != nil {
			log.Printf("Warning: failed to marshal input sequence for game %s: %v", record.GameID, err)
			continue
		}

		originalSize := len(inputJSON)
		compressedData, err := compressData(inputJSON)
		if err != nil {
			log.Printf("Warning: failed to compress data for game %s: %v", record.GameID, err)
			compressedData = inputJSON
		}

		compressedSize := len(compressedData)
		totalOriginalSize += originalSize
		totalCompressedSize += compressedSize
		totalScore += int64(record.Score)

		batch.Queue(`
			INSERT INTO game_records_archive 
			(game_id, player_id, score, stage, enemies_killed, game_time, 
			 start_time, submit_time, frame_count, input_data, input_data_size, 
			 compressed, verified, checksum)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
			ON CONFLICT (game_id) DO NOTHING
		`,
			record.GameID,
			record.PlayerID,
			record.Score,
			record.Stage,
			record.EnemiesKilled,
			record.GameTime,
			time.UnixMilli(record.StartTime),
			time.UnixMilli(record.Timestamp),
			record.FrameCount,
			compressedData,
			originalSize,
			true,
			record.Verified,
			record.Checksum,
		)
		insertCount++
	}

	if batch.Len() == 0 {
		return 0, nil
	}

	results := tx.SendBatch(c.ctx, batch)
	defer results.Close()

	insertedCount := 0
	for i := 0; i < insertCount; i++ {
		tag, err := results.Exec()
		if err != nil {
			log.Printf("Warning: failed to insert record %d: %v", i, err)
			continue
		}
		if tag.RowsAffected() > 0 {
			insertedCount++
		}
	}

	if insertedCount > 0 {
		avgScore := float64(totalScore) / float64(insertedCount)
		compressionRatio := 0.0
		if totalOriginalSize > 0 {
			compressionRatio = float64(totalCompressedSize) / float64(totalOriginalSize)
		}

		archiveDate := time.Now().AddDate(0, 0, -1).Format("2006-01-02")
		_, err = tx.Exec(c.ctx, `
			INSERT INTO archive_summary 
			(archive_date, total_records, total_score, average_score, 
			 compressed_total_size, original_total_size, compression_ratio)
			VALUES ($1, $2, $3, $4, $5, $6, $7)
			ON CONFLICT (archive_date) DO UPDATE SET
				total_records = game_records_archive.total_records + EXCLUDED.total_records,
				total_score = game_records_archive.total_score + EXCLUDED.total_score,
				average_score = (game_records_archive.total_score + EXCLUDED.total_score)::float / 
					(game_records_archive.total_records + EXCLUDED.total_records),
				compressed_total_size = game_records_archive.compressed_total_size + EXCLUDED.compressed_total_size,
				original_total_size = game_records_archive.original_total_size + EXCLUDED.original_total_size,
				compression_ratio = (game_records_archive.compressed_total_size + EXCLUDED.compressed_total_size)::float / 
					(game_records_archive.original_total_size + EXCLUDED.original_total_size)
		`,
			archiveDate,
			insertedCount,
			totalScore,
			avgScore,
			int64(totalCompressedSize),
			int64(totalOriginalSize),
			compressionRatio,
		)
		if err != nil {
			log.Printf("Warning: failed to insert archive summary: %v", err)
		}
	}

	if err := tx.Commit(c.ctx); err != nil {
		return 0, fmt.Errorf("failed to commit transaction: %w", err)
	}

	log.Printf("Archived %d records, original size: %d bytes, compressed size: %d bytes, ratio: %.2f%%",
		insertedCount,
		totalOriginalSize,
		totalCompressedSize,
		float64(totalCompressedSize)/float64(totalOriginalSize)*100,
	)

	return insertedCount, nil
}

func (c *Client) GetReplayData(gameID string) (*redisclient.GameRecord, error) {
	var record ArchivedGameRecord
	err := c.pool.QueryRow(c.ctx, `
		SELECT game_id, player_id, score, stage, enemies_killed, game_time,
			   start_time, submit_time, frame_count, input_data, input_data_size,
			   compressed, verified, checksum
		FROM game_records_archive
		WHERE game_id = $1
	`, gameID).Scan(
		&record.GameID,
		&record.PlayerID,
		&record.Score,
		&record.Stage,
		&record.EnemiesKilled,
		&record.GameTime,
		&record.StartTime,
		&record.SubmitTime,
		&record.FrameCount,
		&record.InputData,
		&record.InputDataSize,
		&record.Compressed,
		&record.Verified,
		&record.Checksum,
	)

	if err != nil {
		return nil, fmt.Errorf("failed to get replay data: %w", err)
	}

	var inputData []byte
	if record.Compressed {
		inputData, err = decompressData(record.InputData)
		if err != nil {
			return nil, fmt.Errorf("failed to decompress input data: %w", err)
		}
	} else {
		inputData = record.InputData
	}

	var inputSequence []redisclient.InputFrame
	if err := json.Unmarshal(inputData, &inputSequence); err != nil {
		return nil, fmt.Errorf("failed to unmarshal input sequence: %w", err)
	}

	return &redisclient.GameRecord{
		GameID:        record.GameID,
		PlayerID:      record.PlayerID,
		Score:         record.Score,
		Stage:         record.Stage,
		EnemiesKilled: record.EnemiesKilled,
		GameTime:      record.GameTime,
		StartTime:     record.StartTime.UnixMilli(),
		Timestamp:     record.SubmitTime.UnixMilli(),
		FrameCount:    record.FrameCount,
		InputSequence: inputSequence,
		Verified:      record.Verified,
		Checksum:      record.Checksum,
	}, nil
}

func (c *Client) Ping() error {
	return c.pool.Ping(c.ctx)
}

func (c *Client) Close() {
	c.pool.Close()
}

func compressData(data []byte) ([]byte, error) {
	var buf bytes.Buffer
	gzipWriter := gzip.NewWriter(&buf)
	
	if _, err := gzipWriter.Write(data); err != nil {
		return nil, err
	}
	
	if err := gzipWriter.Close(); err != nil {
		return nil, err
	}
	
	return buf.Bytes(), nil
}

func decompressData(compressed []byte) ([]byte, error) {
	buf := bytes.NewBuffer(compressed)
	gzipReader, err := gzip.NewReader(buf)
	if err != nil {
		return nil, err
	}
	defer gzipReader.Close()
	
	var result bytes.Buffer
	if _, err := result.ReadFrom(gzipReader); err != nil {
		return nil, err
	}
	
	return result.Bytes(), nil
}

func CompressToBase64(data []byte) (string, error) {
	compressed, err := compressData(data)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(compressed), nil
}

func DecompressFromBase64(encoded string) ([]byte, error) {
	compressed, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	return decompressData(compressed)
}
