package com.retroshooter.dto;

import com.retroshooter.entity.Rank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeasonInfo {
    private String seasonId;
    private String seasonName;
    private LocalDate startDate;
    private LocalDate endDate;
    private int daysRemaining;
    private Rank playerRank;
    private int playerScore;
    private double rankProgress;
    private int scoreToNextRank;
    private long playerRankPosition;
    private List<RankInfo> allRanks;
    private List<RankLeaderboardEntry> topPlayers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankInfo {
        private int level;
        private String name;
        private int minScore;
        private int maxScore;
        private String primaryColor;
        private String secondaryColor;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankLeaderboardEntry {
        private long rank;
        private String playerId;
        private int score;
        private Rank playerRank;
    }
}
