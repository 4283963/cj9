package redisclient

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"time"

	"github.com/go-redis/redis/v8"
	"archive-service/config"
)

const (
	DefaultScanCount     = 100
	DefaultBatchSize     = 50
	DefaultThrottleDelay = 10 * time.Millisecond
)

type GameRecord struct {
	GameID         string       `json:"gameId"`
	PlayerID       string       `json:"playerId"`
	Score          int          `json:"score"`
	Stage          int          `json:"stage"`
	EnemiesKilled  int          `json:"enemiesKilled"`
	GameTime       float32      `json:"gameTime"`
	StartTime      int64        `json:"startTime"`
	Timestamp      int64        `json:"timestamp"`
	FrameCount     int          `json:"frameCount"`
	InputSequence  []InputFrame `json:"inputSequence"`
	RawBinaryData  []byte       `json:"rawBinaryData"`
	Verified       bool         `json:"verified"`
	Checksum       string       `json:"checksum"`
}

type InputFrame struct {
	Frame int    `json:"frame"`
	Input uint8  `json:"input"`
}

type Client struct {
	rdb           *redis.Client
	prefix        string
	ctx           context.Context
	scanCount     int64
	batchSize     int
	throttleDelay time.Duration
}

type ClientOption func(*Client)

func WithScanCount(count int64) ClientOption {
	return func(c *Client) {
		if count > 0 {
			c.scanCount = count
		}
	}
}

func WithBatchSize(size int) ClientOption {
	return func(c *Client) {
		if size > 0 {
			c.batchSize = size
		}
	}
}

func WithThrottleDelay(delay time.Duration) ClientOption {
	return func(c *Client) {
		if delay >= 0 {
			c.throttleDelay = delay
		}
	}
}

func NewClient(cfg *config.Config, opts ...ClientOption) *Client {
	rdb := redis.NewClient(&redis.Options{
		Addr:         fmt.Sprintf("%s:%s", cfg.RedisHost, cfg.RedisPort),
		Password:     cfg.RedisPassword,
		DB:           0,
		PoolSize:     5,
		MinIdleConns: 1,
		MaxRetries:   3,
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
	})

	client := &Client{
		rdb:           rdb,
		prefix:        cfg.RedisKeyPrefix,
		ctx:           context.Background(),
		scanCount:     DefaultScanCount,
		batchSize:     DefaultBatchSize,
		throttleDelay: DefaultThrottleDelay,
	}

	for _, opt := range opts {
		opt(client)
	}

	return client
}

func (c *Client) GetGameRecordsForDate(date string) ([]GameRecord, error) {
	pattern := c.prefix + date + ":*"

	keys, err := c.scanKeys(pattern)
	if err != nil {
		return nil, fmt.Errorf("failed to scan keys for date %s: %w", date, err)
	}

	if len(keys) == 0 {
		return []GameRecord{}, nil
	}

	return c.getGameRecordsBatch(keys)
}

func (c *Client) scanKeys(pattern string) ([]string, error) {
	var keys []string
	var cursor uint64 = 0
	ctx := c.ctx

	for {
		var batchKeys []string
		var err error

		batchKeys, cursor, err = c.rdb.Scan(ctx, cursor, pattern, c.scanCount).Result()
		if err != nil {
			return nil, fmt.Errorf("scan failed at cursor %d: %w", cursor, err)
		}

		if len(batchKeys) > 0 {
			keys = append(keys, batchKeys...)
		}

		if c.throttleDelay > 0 {
			time.Sleep(c.throttleDelay)
		}

		if cursor == 0 {
			break
		}
	}

	return keys, nil
}

func (c *Client) getGameRecordsBatch(keys []string) ([]GameRecord, error) {
	records := make([]GameRecord, 0, len(keys))
	totalKeys := len(keys)
	batchCount := int(math.Ceil(float64(totalKeys) / float64(c.batchSize)))

	for batch := 0; batch < batchCount; batch++ {
		start := batch * c.batchSize
		end := start + c.batchSize
		if end > totalKeys {
			end = totalKeys
		}

		batchKeys := keys[start:end]
		batchRecords, err := c.getBatch(batchKeys)
		if err != nil {
			return nil, fmt.Errorf("batch get failed for keys %v: %w", batchKeys, err)
		}

		records = append(records, batchRecords...)

		if c.throttleDelay > 0 && batch < batchCount-1 {
			time.Sleep(c.throttleDelay)
		}
	}

	return records, nil
}

func (c *Client) getBatch(keys []string) ([]GameRecord, error) {
	values, err := c.rdb.MGet(c.ctx, keys...).Result()
	if err != nil {
		return nil, fmt.Errorf("mget failed: %w", err)
	}

	records := make([]GameRecord, 0, len(keys))
	for i, value := range values {
		if value == nil {
			continue
		}

		data, ok := value.(string)
		if !ok {
			continue
		}

		var record GameRecord
		if err := json.Unmarshal([]byte(data), &record); err != nil {
			return nil, fmt.Errorf("failed to unmarshal record for key %s: %w", keys[i], err)
		}

		records = append(records, record)
	}

	return records, nil
}

func (c *Client) getGameRecord(key string) (*GameRecord, error) {
	data, err := c.rdb.Get(c.ctx, key).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get key %s: %w", key, err)
	}

	var record GameRecord
	if err := json.Unmarshal([]byte(data), &record); err != nil {
		return nil, fmt.Errorf("failed to unmarshal record: %w", err)
	}

	return &record, nil
}

func (c *Client) DeleteGameRecords(keys []string) error {
	if len(keys) == 0 {
		return nil
	}

	totalKeys := len(keys)
	batchSize := c.batchSize * 2
	batchCount := int(math.Ceil(float64(totalKeys) / float64(batchSize)))

	for batch := 0; batch < batchCount; batch++ {
		start := batch * batchSize
		end := start + batchSize
		if end > totalKeys {
			end = totalKeys
		}

		batchKeys := keys[start:end]

		pipe := c.rdb.Pipeline()
		for _, key := range batchKeys {
			pipe.Del(c.ctx, key)
		}

		_, err := pipe.Exec(c.ctx)
		if err != nil {
			return fmt.Errorf("batch delete failed: %w", err)
		}

		if c.throttleDelay > 0 && batch < batchCount-1 {
			time.Sleep(c.throttleDelay)
		}
	}

	return nil
}

func (c *Client) GetKeysForDate(date string) ([]string, error) {
	pattern := c.prefix + date + ":*"
	return c.scanKeys(pattern)
}

func (c *Client) GetReplayKeysForGameIDs(gameIDs []string) []string {
	keys := make([]string, 0, len(gameIDs))
	for _, gameID := range gameIDs {
		keys = append(keys, "game:replay:"+gameID)
	}
	return keys
}

func (c *Client) GetReplayDataBatch(gameIDs []string) (map[string][]byte, error) {
	if len(gameIDs) == 0 {
		return make(map[string][]byte), nil
	}

	replayKeys := c.GetReplayKeysForGameIDs(gameIDs)
	result := make(map[string][]byte, len(gameIDs))

	totalKeys := len(replayKeys)
	batchCount := int(math.Ceil(float64(totalKeys) / float64(c.batchSize)))

	for batch := 0; batch < batchCount; batch++ {
		start := batch * c.batchSize
		end := start + c.batchSize
		if end > totalKeys {
			end = totalKeys
		}

		batchKeys := replayKeys[start:end]
		batchGameIDs := gameIDs[start:end]

		values, err := c.rdb.MGet(c.ctx, batchKeys...).Result()
		if err != nil {
			return nil, fmt.Errorf("mget replay data failed: %w", err)
		}

		for i, value := range values {
			if value == nil {
				continue
			}
			if data, ok := value.(string); ok {
				result[batchGameIDs[i]] = []byte(data)
			}
		}

		if c.throttleDelay > 0 && batch < batchCount-1 {
			time.Sleep(c.throttleDelay)
		}
	}

	return result, nil
}

func (c *Client) Ping() error {
	ctx, cancel := context.WithTimeout(c.ctx, 2*time.Second)
	defer cancel()
	return c.rdb.Ping(ctx).Err()
}

func (c *Client) Close() error {
	return c.rdb.Close()
}

func GetYesterday() string {
	now := time.Now()
	yesterday := now.AddDate(0, 0, -1)
	return yesterday.Format("2006-01-02")
}

func GetDateNDaysAgo(n int) string {
	now := time.Now()
	date := now.AddDate(0, 0, -n)
	return date.Format("2006-01-02")
}
