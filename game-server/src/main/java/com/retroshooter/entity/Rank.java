package com.retroshooter.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum Rank {
    BRONZE(1, "青铜", 0, 1000, "#CD7F32", "#8B4513"),
    SILVER(2, "白银", 1000, 3000, "#C0C0C0", "#808080"),
    GOLD(3, "黄金", 3000, 8000, "#FFD700", "#B8860B"),
    PLATINUM(4, "铂金", 8000, 20000, "#E5E4E2", "#708090"),
    DIAMOND(5, "王者", 20000, Integer.MAX_VALUE, "#00FFFF", "#4169E1");

    private final int level;
    private final String name;
    private final int minScore;
    private final int maxScore;
    private final String primaryColor;
    private final String secondaryColor;

    public static Rank fromScore(int score) {
        for (Rank rank : values()) {
            if (score >= rank.minScore && score < rank.maxScore) {
                return rank;
            }
        }
        return BRONZE;
    }

    public static Rank getByLevel(int level) {
        for (Rank rank : values()) {
            if (rank.level == level) {
                return rank;
            }
        }
        return BRONZE;
    }

    public boolean isHigherThan(Rank other) {
        return this.level > other.level;
    }

    public double getProgress(int currentScore) {
        if (currentScore <= minScore) return 0.0;
        if (currentScore >= maxScore) return 1.0;
        return (double) (currentScore - minScore) / (maxScore - minScore);
    }

    public int getScoreToNextRank(int currentScore) {
        if (this == DIAMOND) return 0;
        return Math.max(0, maxScore - currentScore);
    }
}
