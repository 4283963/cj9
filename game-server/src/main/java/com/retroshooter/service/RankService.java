package com.retroshooter.service;

import com.retroshooter.dto.RankPromotionEvent;
import com.retroshooter.dto.SeasonInfo;
import com.retroshooter.entity.Rank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${redis.keys.leaderboard-all}")
    private String leaderboardAllKey;

    @Value("${game.season.name:赛季1}")
    private String seasonName;

    @Value("${game.season.start-date:2026-01-01}")
    private String seasonStartDateStr;

    @Value("${game.season.end-date:2026-12-31}")
    private String seasonEndDateStr;

    public int getPlayerTotalScore(String playerId) {
        try {
            ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    zSetOps.reverseRangeWithScores(leaderboardAllKey, 0, -1);

            if (tuples == null) return 0;

            int totalScore = 0;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String member = tuple.getValue();
                Double score = tuple.getScore();
                if (member != null && score != null && member.startsWith(playerId + "|")) {
                    totalScore = Math.max(totalScore, score.intValue());
                }
            }
            return totalScore;
        } catch (Exception e) {
            log.error("Failed to get player total score for {}: {}", playerId, e.getMessage());
            return 0;
        }
    }

    public RankPromotionEvent checkRankPromotion(String playerId, int newGameScore) {
        int previousTotalScore = getPlayerTotalScore(playerId);
        int newTotalScore = Math.max(previousTotalScore, newGameScore);

        Rank previousRank = Rank.fromScore(previousTotalScore);
        Rank newRank = Rank.fromScore(newTotalScore);

        boolean promoted = newRank.isHigherThan(previousRank);

        RankPromotionEvent.RankPromotionEventBuilder builder = RankPromotionEvent.builder()
                .promoted(promoted)
                .previousRank(previousRank)
                .newRank(newRank)
                .scoreGained(Math.max(0, newTotalScore - previousTotalScore))
                .totalScore(newTotalScore);

        if (promoted) {
            builder.primaryColor(newRank.getPrimaryColor())
                    .secondaryColor(newRank.getSecondaryColor())
                    .particleEffect(getParticleEffectForRank(newRank))
                    .durationMs(getDurationForRank(newRank));

            log.info("Player {} promoted from {} to {}! Score: {} -> {}",
                    playerId, previousRank.getName(), newRank.getName(),
                    previousTotalScore, newTotalScore);
        }

        return builder.build();
    }

    private String getParticleEffectForRank(Rank rank) {
        switch (rank) {
            case SILVER:
                return "silver_shimmer";
            case GOLD:
                return "golden_burst";
            case PLATINUM:
                return "platinum_sparkle";
            case DIAMOND:
                return "diamond_explosion";
            default:
                return "default_glow";
        }
    }

    private int getDurationForRank(Rank rank) {
        switch (rank) {
            case SILVER:
                return 2500;
            case GOLD:
                return 3500;
            case PLATINUM:
                return 4500;
            case DIAMOND:
                return 6000;
            default:
                return 2000;
        }
    }

    public SeasonInfo getSeasonInfo(String playerId) {
        LocalDate startDate = LocalDate.parse(seasonStartDateStr);
        LocalDate endDate = LocalDate.parse(seasonEndDateStr);
        LocalDate today = LocalDate.now();
        int daysRemaining = (int) ChronoUnit.DAYS.between(today, endDate);

        int playerScore = getPlayerTotalScore(playerId);
        Rank playerRank = Rank.fromScore(playerScore);

        long playerRankPosition = getPlayerRankPosition(playerId);

        List<SeasonInfo.RankInfo> allRanks = Arrays.stream(Rank.values())
                .map(rank -> SeasonInfo.RankInfo.builder()
                        .level(rank.getLevel())
                        .name(rank.getName())
                        .minScore(rank.getMinScore())
                        .maxScore(rank.getMaxScore())
                        .primaryColor(rank.getPrimaryColor())
                        .secondaryColor(rank.getSecondaryColor())
                        .build())
                .collect(Collectors.toList());

        List<SeasonInfo.RankLeaderboardEntry> topPlayers = getTopPlayers(10);

        return SeasonInfo.builder()
                .seasonId("S1")
                .seasonName(seasonName)
                .startDate(startDate)
                .endDate(endDate)
                .daysRemaining(Math.max(0, daysRemaining))
                .playerRank(playerRank)
                .playerScore(playerScore)
                .rankProgress(playerRank.getProgress(playerScore))
                .scoreToNextRank(playerRank.getScoreToNextRank(playerScore))
                .playerRankPosition(playerRankPosition)
                .allRanks(allRanks)
                .topPlayers(topPlayers)
                .build();
    }

    private long getPlayerRankPosition(String playerId) {
        try {
            ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    zSetOps.reverseRangeWithScores(leaderboardAllKey, 0, -1);

            if (tuples == null) return -1;

            long position = 1;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String member = tuple.getValue();
                if (member != null && member.startsWith(playerId + "|")) {
                    return position;
                }
                position++;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private List<SeasonInfo.RankLeaderboardEntry> getTopPlayers(int limit) {
        try {
            ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    zSetOps.reverseRangeWithScores(leaderboardAllKey, 0, limit - 1);

            if (tuples == null) return Collections.emptyList();

            List<SeasonInfo.RankLeaderboardEntry> entries = new ArrayList<>();
            long rank = 1;
            Set<String> seenPlayers = new HashSet<>();

            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String member = tuple.getValue();
                Double score = tuple.getScore();

                if (member != null && score != null) {
                    String[] parts = member.split("\\|");
                    if (parts.length >= 1) {
                        String playerId = parts[0];
                        if (!seenPlayers.contains(playerId)) {
                            seenPlayers.add(playerId);
                            int playerScore = score.intValue();
                            entries.add(SeasonInfo.RankLeaderboardEntry.builder()
                                    .rank(rank++)
                                    .playerId(playerId)
                                    .score(playerScore)
                                    .playerRank(Rank.fromScore(playerScore))
                                    .build());
                        }
                    }
                }
            }
            return entries;
        } catch (Exception e) {
            log.error("Failed to get top players: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public Rank calculateRank(int score) {
        return Rank.fromScore(score);
    }

    public double getRankProgress(int score) {
        Rank rank = Rank.fromScore(score);
        return rank.getProgress(score);
    }

    public int getScoreToNextRank(int score) {
        Rank rank = Rank.fromScore(score);
        return rank.getScoreToNextRank(score);
    }
}
