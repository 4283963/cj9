package config

import (
	"log"
	"os"
	"strconv"

	"github.com/joho/godotenv"
)

type Config struct {
	RedisHost     string
	RedisPort     string
	RedisPassword string

	PostgresHost     string
	PostgresPort     string
	PostgresUser     string
	PostgresPassword string
	PostgresDB       string

	RedisKeyPrefix       string
	BatchSize            int
	RedisScanCount       int64
	RedisBatchSize       int
	RedisThrottleDelayMs int
	PostgresBatchSize    int
	ArchiveCron          string
	TimeZone             string
}

func Load() *Config {
	if err := godotenv.Load(); err != nil {
		log.Printf("Warning: .env file not found: %v", err)
	}

	return &Config{
		RedisHost:     getEnv("REDIS_HOST", "localhost"),
		RedisPort:     getEnv("REDIS_PORT", "6379"),
		RedisPassword: getEnv("REDIS_PASSWORD", ""),

		PostgresHost:     getEnv("POSTGRES_HOST", "localhost"),
		PostgresPort:     getEnv("POSTGRES_PORT", "5432"),
		PostgresUser:     getEnv("POSTGRES_USER", "retroshooter"),
		PostgresPassword: getEnv("POSTGRES_PASSWORD", "retroshooter"),
		PostgresDB:       getEnv("POSTGRES_DB", "retroshooter"),

		RedisKeyPrefix:       getEnv("REDIS_KEY_PREFIX", "game:detail:"),
		BatchSize:            getEnvInt("BATCH_SIZE", 100),
		RedisScanCount:       int64(getEnvInt("REDIS_SCAN_COUNT", 100)),
		RedisBatchSize:       getEnvInt("REDIS_BATCH_SIZE", 50),
		RedisThrottleDelayMs: getEnvInt("REDIS_THROTTLE_MS", 10),
		PostgresBatchSize:    getEnvInt("POSTGRES_BATCH_SIZE", 100),
		ArchiveCron:          getEnv("ARCHIVE_CRON", "0 0 1 * * *"),
		TimeZone:             getEnv("TIME_ZONE", "Asia/Shanghai"),
	}
}

func getEnv(key, defaultValue string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value, exists := os.LookupEnv(key); exists {
		if intValue, err := strconv.Atoi(value); err == nil {
			return intValue
		}
	}
	return defaultValue
}
