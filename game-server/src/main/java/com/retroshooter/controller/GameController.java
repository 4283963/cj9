package com.retroshooter.controller;

import com.retroshooter.dto.ApiResponse;
import com.retroshooter.dto.GameSubmitResponse;
import com.retroshooter.dto.SeasonInfo;
import com.retroshooter.entity.GameRecord;
import com.retroshooter.entity.LeaderboardEntry;
import com.retroshooter.service.GameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
public class GameController {
    private final GameService gameService;

    @PostMapping(value = "/submit", consumes = "application/octet-stream")
    public ResponseEntity<ApiResponse<GameSubmitResponse>> submitGame(
            InputStream inputStream,
            @RequestHeader(value = "Content-Length", required = false) Integer contentLength) {
        
        try {
            byte[] binaryData = inputStream.readAllBytes();
            
            log.info("Received game submission, size: {} bytes", binaryData.length);
            
            if (binaryData.length < 4) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("数据过短，无法解析"));
            }
            
            GameSubmitResponse result = gameService.submitGame(binaryData);
            
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success(result.getMessage(), result));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(result.getMessage()));
            }
            
        } catch (IOException e) {
            log.error("Failed to read game submission data", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("读取数据失败: " + e.getMessage()));
        }
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<ApiResponse<List<LeaderboardEntry>>> getLeaderboard(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "100") int limit) {
        
        log.debug("Fetching leaderboard, period: {}, limit: {}", period, limit);
        
        List<LeaderboardEntry> entries = gameService.getLeaderboard(period, limit);
        return ResponseEntity.ok(ApiResponse.success(entries));
    }

    @GetMapping("/replay/{gameId}")
    public ResponseEntity<ApiResponse<GameRecord>> getReplayData(@PathVariable String gameId) {
        log.debug("Fetching replay data for gameId: {}", gameId);
        
        Optional<GameRecord> record = gameService.getReplayData(gameId);
        
        if (record.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(record.get()));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/player/{playerId}/best")
    public ResponseEntity<ApiResponse<LeaderboardEntry>> getPlayerBest(
            @PathVariable String playerId,
            @RequestParam(defaultValue = "today") String period) {
        
        log.debug("Fetching best score for player: {}, period: {}", playerId, period);
        
        Optional<LeaderboardEntry> best = gameService.getPlayerBest(playerId, period);
        
        if (best.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(best.get()));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/season")
    public ResponseEntity<ApiResponse<SeasonInfo>> getSeasonInfo(
            @RequestParam(defaultValue = "Guest") String playerId) {
        log.debug("Fetching season info for player: {}", playerId);
        SeasonInfo seasonInfo = gameService.getSeasonInfo(playerId);
        return ResponseEntity.ok(ApiResponse.success(seasonInfo));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> healthCheck() {
        return ResponseEntity.ok(ApiResponse.success("OK", "Service is running"));
    }
}
