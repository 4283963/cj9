package com.retroshooter.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardEntry implements Serializable, Comparable<LeaderboardEntry> {
    private String gameId;
    private String playerId;
    private int score;
    private int stage;
    private int enemiesKilled;
    private float gameTime;
    private long timestamp;
    private long rank;

    @Override
    public int compareTo(LeaderboardEntry other) {
        return Integer.compare(other.score, this.score);
    }
}
