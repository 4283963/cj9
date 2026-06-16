package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"archive-service/config"
	postgresclient "archive-service/internal/postgres"
	redisclient "archive-service/internal/redis"
	"archive-service/internal/scheduler"
)

func main() {
	var (
		runOnce     bool
		archiveDate string
		cleanDays   int
		serverMode  bool
	)

	flag.BoolVar(&runOnce, "once", false, "Run archive once and exit")
	flag.StringVar(&archiveDate, "date", "", "Archive specific date (format: YYYY-MM-DD)")
	flag.IntVar(&cleanDays, "clean", 0, "Clean old data older than N days")
	flag.BoolVar(&serverMode, "server", true, "Run as daemon server with scheduler")
	flag.Parse()

	cfg := config.Load()

	log.Println("=== Retro Shooter Archive Service ===")
	log.Printf("Redis: %s:%s", cfg.RedisHost, cfg.RedisPort)
	log.Printf("PostgreSQL: %s:%s/%s", cfg.PostgresHost, cfg.PostgresPort, cfg.PostgresDB)

	redisClient := redisclient.NewClient(cfg)
	defer redisClient.Close()

	if err := redisClient.Ping(); err != nil {
		log.Fatalf("Failed to connect to Redis: %v", err)
	}
	log.Println("Connected to Redis successfully")

	postgresClient, err := postgresclient.NewClient(cfg)
	if err != nil {
		log.Fatalf("Failed to connect to PostgreSQL: %v", err)
	}
	defer postgresClient.Close()

	if err := postgresClient.Ping(); err != nil {
		log.Fatalf("Failed to ping PostgreSQL: %v", err)
	}
	log.Println("Connected to PostgreSQL successfully")

	if cleanDays > 0 {
		log.Printf("Cleaning data older than %d days", cleanDays)
		archiver := scheduler.NewArchiver(cfg, redisClient, postgresClient)
		if err := archiver.CleanOldData(cleanDays); err != nil {
			log.Fatalf("Failed to clean old data: %v", err)
		}
		log.Println("Clean completed successfully")
		return
	}

	if archiveDate != "" {
		log.Printf("Archiving data for date: %s", archiveDate)
		archiver := scheduler.NewArchiver(cfg, redisClient, postgresClient)
		if err := archiver.RunArchiveForDate(archiveDate); err != nil {
			log.Fatalf("Failed to archive data for date %s: %v", archiveDate, err)
		}
		log.Println("Archive completed successfully")
		return
	}

	if runOnce {
		log.Println("Running archive once...")
		archiver := scheduler.NewArchiver(cfg, redisClient, postgresClient)
		if err := archiver.RunArchive(); err != nil {
			log.Fatalf("Failed to run archive: %v", err)
		}
		log.Println("Archive completed successfully")
		return
	}

	if serverMode {
		archiver := scheduler.NewArchiver(cfg, redisClient, postgresClient)
		
		if err := archiver.Start(); err != nil {
			log.Fatalf("Failed to start scheduler: %v", err)
		}
		defer archiver.Stop()

		go startHTTPServer(cfg, archiver)

		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
		sig := <-sigChan
		log.Printf("Received signal: %v, shutting down...", sig)
	}

	log.Println("Service stopped")
}

func startHTTPServer(cfg *config.Config, archiver *scheduler.Archiver) {
	mux := http.NewServeMux()

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `{"status":"ok","service":"archive-service"}`)
	})

	mux.HandleFunc("/archive/run", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		
		date := r.URL.Query().Get("date")
		var err error
		
		if date != "" {
			log.Printf("Manual archive triggered for date: %s", date)
			err = archiver.RunArchiveForDate(date)
		} else {
			log.Println("Manual archive triggered")
			err = archiver.RunArchive()
		}
		
		w.Header().Set("Content-Type", "application/json")
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			fmt.Fprintf(w, `{"success":false,"error":"%s"}`, err.Error())
			return
		}
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `{"success":true,"message":"Archive completed"}`)
	})

	addr := ":8081"
	log.Printf("HTTP server starting on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Printf("HTTP server error: %v", err)
	}
}
