package com.retroshooter.dto;

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
}
