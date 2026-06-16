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
public class GameSubmitResponse {
    private boolean success;
    private String message;
    private long rank;
    private int score;
    private String gameId;
    private boolean verified;
    private String verificationDetails;
    private Rank currentRank;
    private double rankProgress;
    private int scoreToNextRank;
    private RankPromotionEvent promotionEvent;
}
