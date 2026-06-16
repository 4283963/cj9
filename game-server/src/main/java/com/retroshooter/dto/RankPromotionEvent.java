package com.retroshooter.dto;

import com.retroshooter.entity.Rank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankPromotionEvent {
    private boolean promoted;
    private Rank previousRank;
    private Rank newRank;
    private int scoreGained;
    private int totalScore;
    private String primaryColor;
    private String secondaryColor;
    private String particleEffect;
    private int durationMs;
}
