package scheduler

import (
	"log"
	"time"

	"github.com/robfig/cron/v3"
	"archive-service/config"
	postgresclient "archive-service/internal/postgres"
	redisclient "archive-service/internal/redis"
)

type Archiver struct {
	cfg            *config.Config
	redisClient    *redisclient.Client
	postgresClient *postgresclient.Client
	cron           *cron.Cron
}

func NewArchiver(cfg *config.Config, redisClient *redisclient.Client, postgresClient *postgresclient.Client) *Archiver {
	return &Archiver{
		cfg:            cfg,
		redisClient:    redisClient,
		postgresClient: postgresClient,
		cron:           cron.New(cron.WithLocation(time.UTC)),
	}
}

func (a *Archiver) Start() error {
	_, err := a.cron.AddFunc(a.cfg.ArchiveCron, func() {
		log.Println("Starting scheduled archive job...")
		if err := a.RunArchive(); err != nil {
			log.Printf("Archive job failed: %v", err)
		} else {
			log.Println("Archive job completed successfully")
		}
	})

	if err != nil {
		return err
	}

	a.cron.Start()
	log.Printf("Scheduler started with cron: %s", a.cfg.ArchiveCron)
	return nil
}

func (a *Archiver) Stop() {
	ctx := a.cron.Stop()
	<-ctx.Done()
	log.Println("Scheduler stopped")
}

func (a *Archiver) RunArchive() error {
	startTime := time.Now()
	log.Println("=== Starting archive process ===")

	date := redisclient.GetYesterday()
	log.Printf("Archiving data for date: %s", date)

	keys, err := a.redisClient.GetKeysForDate(date)
	if err != nil {
		return err
	}

	if len(keys) == 0 {
		log.Printf("No records found for date %s, skipping", date)
		return nil
	}

	log.Printf("Found %d keys to archive", len(keys))

	records, err := a.redisClient.GetGameRecordsForDate(date)
	if err != nil {
		return err
	}

	log.Printf("Retrieved %d records from Redis", len(records))

	insertedCount, err := a.postgresClient.ArchiveRecords(records)
	if err != nil {
		return err
	}

	log.Printf("Inserted %d records into PostgreSQL", insertedCount)

	if insertedCount > 0 {
		gameIDs := make([]string, 0, len(records))
		for _, record := range records {
			gameIDs = append(gameIDs, record.GameID)
		}

		replayKeys := a.redisClient.GetReplayKeysForGameIDs(gameIDs)
		allKeysToDelete := append(keys, replayKeys...)

		log.Printf("Deleting %d keys from Redis", len(allKeysToDelete))
		if err := a.redisClient.DeleteGameRecords(allKeysToDelete); err != nil {
			log.Printf("Warning: failed to delete records from Redis: %v", err)
		} else {
			log.Printf("Successfully deleted %d keys from Redis", len(allKeysToDelete))
		}
	}

	duration := time.Since(startTime)
	log.Printf("=== Archive process completed in %v ===", duration)

	return nil
}

func (a *Archiver) RunArchiveForDate(date string) error {
	log.Printf("Manually archiving data for date: %s", date)

	keys, err := a.redisClient.GetKeysForDate(date)
	if err != nil {
		return err
	}

	if len(keys) == 0 {
		log.Printf("No records found for date %s", date)
		return nil
	}

	records, err := a.redisClient.GetGameRecordsForDate(date)
	if err != nil {
		return err
	}

	insertedCount, err := a.postgresClient.ArchiveRecords(records)
	if err != nil {
		return err
	}

	if insertedCount > 0 {
		gameIDs := make([]string, 0, len(records))
		for _, record := range records {
			gameIDs = append(gameIDs, record.GameID)
		}

		replayKeys := a.redisClient.GetReplayKeysForGameIDs(gameIDs)
		allKeysToDelete := append(keys, replayKeys...)

		if err := a.redisClient.DeleteGameRecords(allKeysToDelete); err != nil {
			log.Printf("Warning: failed to delete records from Redis: %v", err)
		}
	}

	log.Printf("Manually archived %d records for date %s", insertedCount, date)
	return nil
}

func (a *Archiver) CleanOldData(daysOld int) error {
	if daysOld <= 0 {
		daysOld = 7
	}

	date := redisclient.GetDateNDaysAgo(daysOld)
	log.Printf("Cleaning old data for date: %s", date)

	keys, err := a.redisClient.GetKeysForDate(date)
	if err != nil {
		return err
	}

	if len(keys) > 0 {
		records, err := a.redisClient.GetGameRecordsForDate(date)
		if err != nil {
			return err
		}

		gameIDs := make([]string, 0, len(records))
		for _, record := range records {
			gameIDs = append(gameIDs, record.GameID)
		}

		replayKeys := a.redisClient.GetReplayKeysForGameIDs(gameIDs)
		allKeysToDelete := append(keys, replayKeys...)

		if err := a.redisClient.DeleteGameRecords(allKeysToDelete); err != nil {
			return err
		}

		log.Printf("Cleaned %d old records from Redis", len(allKeysToDelete))
	} else {
		log.Printf("No old data found for date %s", date)
	}

	return nil
}
