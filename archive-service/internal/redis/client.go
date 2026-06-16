package redisclient

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/go-redis/redis/v8"
	"archive-service/config"
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
	rdb    *redis.Client
	prefix string
	ctx    context.Context
}

func NewClient(cfg *config.Config) *Client {
	rdb := redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%s", cfg.RedisHost, cfg.RedisPort),
		Password: cfg.RedisPassword,
		DB:       0,
	})

	return &Client{
		rdb:    rdb,
		prefix: cfg.RedisKeyPrefix,
		ctx:    context.Background(),
	}
}

func (c *Client) GetGameRecordsForDate(date string) ([]GameRecord, error) {
	pattern := c.prefix + date + ":*"
	keys, err := c.rdb.Keys(c.ctx, pattern).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get keys for date %s: %w", date, err)
	}

	if len(keys) == 0 {
		return []GameRecord{}, nil
	}

	records := make([]GameRecord, 0, len(keys))
	for _, key := range keys {
		record, err := c.getGameRecord(key)
		if err != nil {
			return nil, fmt.Errorf("failed to get record for key %s: %w", key, err)
		}
		records = append(records, *record)
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

	pipe := c.rdb.Pipeline()
	for _, key := range keys {
		pipe.Del(c.ctx, key)
	}

	_, err := pipe.Exec(c.ctx)
	if err != nil {
		return fmt.Errorf("failed to delete keys: %w", err)
	}

	return nil
}

func (c *Client) GetKeysForDate(date string) ([]string, error) {
	pattern := c.prefix + date + ":*"
	return c.rdb.Keys(c.ctx, pattern).Result()
}

func (c *Client) GetReplayKeysForGameIDs(gameIDs []string) []string {
	keys := make([]string, 0, len(gameIDs))
	for _, gameID := range gameIDs {
		keys = append(keys, "game:replay:"+gameID)
	}
	return keys
}

func (c *Client) Ping() error {
	return c.rdb.Ping(c.ctx).Err()
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
