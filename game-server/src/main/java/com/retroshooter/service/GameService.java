package com.retroshooter.service;

import com.retroshooter.dto.GameSubmitResponse;
import com.retroshooter.entity.GameRecord;
import com.retroshooter.entity.LeaderboardEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {
    private final BinarySerializer binarySerializer;
    private final AntiCheatService antiCheatService;
    private final LeaderboardService leaderboardService;

    public GameSubmitResponse submitGame(byte[] binaryData) {
        log.debug("Received game submission, size: {} bytes", binaryData.length);
        
        BinarySerializer.DeserializationResult deserResult = binarySerializer.deserialize(binaryData);
        if (!deserResult.isSuccess()) {
            log.warn("Deserialization failed: {}", deserResult.getErrorMessage());
            return GameSubmitResponse.builder()
                    .success(false)
                    .message("数据解析失败: " + deserResult.getErrorMessage())
                    .build();
        }
        
        GameRecord record = deserResult.getRecord();
        log.info("Processing game submission: gameId={}, player={}, score={}", 
                record.getGameId(), record.getPlayerId(), record.getScore());
        
        AntiCheatService.VerificationResult verifyResult = antiCheatService.verify(record);
        
        String verificationDetails = verifyResult.getDetails();
        if (!verifyResult.getViolations().isEmpty()) {
            verificationDetails += ". Violations: " + String.join("; ", verifyResult.getViolations());
        }
        
        if (!verifyResult.isPassed()) {
            log.warn("Anti-cheat verification failed for game {}: {}", 
                    record.getGameId(), verifyResult.getDetails());
            return GameSubmitResponse.builder()
                    .success(false)
                    .message("反作弊验证失败: " + verifyResult.getDetails())
                    .gameId(record.getGameId())
                    .score(record.getScore())
                    .verified(false)
                    .verificationDetails(verificationDetails)
                    .build();
        }
        
        record.setVerified(true);
        Long rank = leaderboardService.addScore(record);
        
        log.info("Game {} accepted, rank: {}, player: {}, score: {}", 
                record.getGameId(), rank, record.getPlayerId(), record.getScore());
        
        return GameSubmitResponse.builder()
                .success(true)
                .message("分数提交成功")
                .rank(rank)
                .score(record.getScore())
                .gameId(record.getGameId())
                .verified(true)
                .verificationDetails(verificationDetails)
                .build();
    }

    public List<LeaderboardEntry> getLeaderboard(String period, int limit) {
        return leaderboardService.getLeaderboard(period, limit);
    }

    public Optional<GameRecord> getReplayData(String gameId) {
        return leaderboardService.getReplayData(gameId);
    }

    public Optional<LeaderboardEntry> getPlayerBest(String playerId, String period) {
        return leaderboardService.getPlayerBest(playerId, period);
    }
}
