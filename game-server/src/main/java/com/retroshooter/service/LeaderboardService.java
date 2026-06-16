package com.retroshooter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retroshooter.entity.GameRecord;
import com.retroshooter.entity.LeaderboardEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${redis.keys.leaderboard-daily}")
    private String leaderboardDailyKey;

    @Value("${redis.keys.leaderboard-weekly}")
    private String leaderboardWeeklyKey;

    @Value("${redis.keys.leaderboard-all}")
    private String leaderboardAllKey;

    @Value("${redis.keys.game-detail-prefix}")
    private String gameDetailPrefix;

    @Value("${redis.keys.player-best-prefix}")
    private String playerBestPrefix;

    @Value("${redis.keys.replay-data-prefix}")
    private String replayDataPrefix;

    public Long addScore(GameRecord record) {
        String member = createMember(record);
        
        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
        
        zSetOps.add(getDailyKey(), member, record.getScore());
        zSetOps.add(getWeeklyKey(), member, record.getScore());
        zSetOps.add(leaderboardAllKey, member, record.getScore());
        
        storeGameDetail(record);
        storeReplayData(record);
        updatePlayerBest(record);
        
        Long rank = zSetOps.reverseRank(getDailyKey(), member);
        return rank != null ? rank + 1 : -1;
    }

    public List<LeaderboardEntry> getLeaderboard(String period, int limit) {
        String key;
        switch (period.toLowerCase()) {
            case "week":
                key = getWeeklyKey();
                break;
            case "all":
                key = leaderboardAllKey;
                break;
            case "today":
            default:
                key = getDailyKey();
                break;
        }
        
        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> tuples = 
                zSetOps.reverseRangeWithScores(key, 0, limit - 1);
        
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<LeaderboardEntry> entries = new ArrayList<>();
        long rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();
            Double score = tuple.getScore();
            
            if (member != null && score != null) {
                LeaderboardEntry entry = parseMember(member);
                if (entry != null) {
                    entry.setScore(score.intValue());
                    entry.setRank(rank++);
                    entries.add(entry);
                }
            }
        }
        
        return entries;
    }

    public Optional<GameRecord> getReplayData(String gameId) {
        String key = replayDataPrefix + gameId;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return Optional.ofNullable(objectMapper.readValue(json, GameRecord.class));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize replay data for gameId: {}", gameId, e);
        }
        return Optional.empty();
    }

    public Optional<LeaderboardEntry> getPlayerBest(String playerId, String period) {
        String key = playerBestPrefix + playerId;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return Optional.ofNullable(objectMapper.readValue(json, LeaderboardEntry.class));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to get player best for: {}", playerId, e);
        }
        return Optional.empty();
    }

    public long getRank(String playerId, String period) {
        String key = period.equals("week") ? getWeeklyKey() : 
                     period.equals("all") ? leaderboardAllKey : getDailyKey();
        
        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
        
        Set<ZSetOperations.TypedTuple<String>> tuples = zSetOps.reverseRangeWithScores(key, 0, -1);
        if (tuples != null) {
            long rank = 1;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String member = tuple.getValue();
                if (member != null && member.contains(playerId + "|")) {
                    return rank;
                }
                rank++;
            }
        }
        return -1;
    }

    public Set<String> getGameIdsForDate(LocalDate date) {
        String pattern = gameDetailPrefix + date.toString() + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null) {
            return Collections.emptySet();
        }
        return keys.stream()
                .map(key -> key.substring(key.lastIndexOf(':') + 1))
                .collect(Collectors.toSet());
    }

    public void deleteGameRecords(Set<String> gameIds, LocalDate date) {
        List<String> keysToDelete = new ArrayList<>();
        String datePrefix = date.toString();
        
        for (String gameId : gameIds) {
            keysToDelete.add(gameDetailPrefix + datePrefix + ":" + gameId);
            keysToDelete.add(replayDataPrefix + gameId);
        }
        
        if (!keysToDelete.isEmpty()) {
            redisTemplate.delete(keysToDelete);
            log.info("Deleted {} game records for date {}", keysToDelete.size() / 2, date);
        }
    }

    public void removeFromLeaderboards(Set<String> gameIds, LocalDate date) {
        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
        
        Set<ZSetOperations.TypedTuple<String>> dailyEntries = 
                zSetOps.reverseRangeWithScores(getDailyKey(), 0, -1);
        
        if (dailyEntries != null) {
            for (ZSetOperations.TypedTuple<String> entry : dailyEntries) {
                String member = entry.getValue();
                if (member != null) {
                    for (String gameId : gameIds) {
                        if (member.contains(gameId)) {
                            zSetOps.remove(getDailyKey(), member);
                            break;
                        }
                    }
                }
            }
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void clearDailyLeaderboard() {
        log.info("Clearing daily leaderboard");
        redisTemplate.delete(getDailyKey());
    }

    @Scheduled(cron = "0 0 0 ? * MON")
    public void clearWeeklyLeaderboard() {
        log.info("Clearing weekly leaderboard");
        redisTemplate.delete(getWeeklyKey());
    }

    private String getDailyKey() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        return leaderboardDailyKey + ":" + today.toString();
    }

    private String getWeeklyKey() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        int weekOfYear = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = today.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
        return leaderboardWeeklyKey + ":" + year + "-W" + weekOfYear;
    }

    private String createMember(GameRecord record) {
        LeaderboardEntry entry = LeaderboardEntry.builder()
                .gameId(record.getGameId())
                .playerId(record.getPlayerId())
                .score(record.getScore())
                .stage(record.getStage())
                .enemiesKilled(record.getEnemiesKilled())
                .gameTime(record.getGameTime())
                .timestamp(record.getTimestamp())
                .build();
        
        try {
            return record.getGameId() + "|" + 
                   record.getPlayerId() + "|" + 
                   objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize leaderboard entry", e);
            return record.getGameId() + "|" + record.getPlayerId();
        }
    }

    private LeaderboardEntry parseMember(String member) {
        try {
            int firstPipe = member.indexOf('|');
            int secondPipe = member.indexOf('|', firstPipe + 1);
            
            if (secondPipe != -1) {
                String json = member.substring(secondPipe + 1);
                return objectMapper.readValue(json, LeaderboardEntry.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse leaderboard member: {}", member, e);
            
            try {
                int firstPipe = member.indexOf('|');
                if (firstPipe != -1) {
                    return LeaderboardEntry.builder()
                            .gameId(member.substring(0, firstPipe))
                            .playerId(member.substring(firstPipe + 1))
                            .build();
                }
            } catch (Exception ex) {
                log.error("Failed to parse basic member info", ex);
            }
        }
        return null;
    }

    private void storeGameDetail(GameRecord record) {
        try {
            LocalDate date = new Date(record.getTimestamp()).toInstant()
                    .atZone(ZoneId.of("Asia/Shanghai"))
                    .toLocalDate();
            
            String key = gameDetailPrefix + date.toString() + ":" + record.getGameId();
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(key, json, 7, TimeUnit.DAYS);
        } catch (JsonProcessingException e) {
            log.error("Failed to store game detail for gameId: {}", record.getGameId(), e);
        }
    }

    private void storeReplayData(GameRecord record) {
        try {
            String key = replayDataPrefix + record.getGameId();
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(key, json, 30, TimeUnit.DAYS);
        } catch (JsonProcessingException e) {
            log.error("Failed to store replay data for gameId: {}", record.getGameId(), e);
        }
    }

    private void updatePlayerBest(GameRecord record) {
        try {
            String key = playerBestPrefix + record.getPlayerId();
            String existingJson = redisTemplate.opsForValue().get(key);
            
            LeaderboardEntry newEntry = LeaderboardEntry.builder()
                    .gameId(record.getGameId())
                    .playerId(record.getPlayerId())
                    .score(record.getScore())
                    .stage(record.getStage())
                    .enemiesKilled(record.getEnemiesKilled())
                    .gameTime(record.getGameTime())
                    .timestamp(record.getTimestamp())
                    .build();
            
            if (existingJson != null) {
                LeaderboardEntry existing = objectMapper.readValue(existingJson, LeaderboardEntry.class);
                if (existing.getScore() >= record.getScore()) {
                    return;
                }
            }
            
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(newEntry), 30, TimeUnit.DAYS);
        } catch (JsonProcessingException e) {
            log.error("Failed to update player best for: {}", record.getPlayerId(), e);
        }
    }
}
