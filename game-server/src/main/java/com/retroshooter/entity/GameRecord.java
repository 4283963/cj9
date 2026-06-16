package com.retroshooter.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameRecord implements Serializable {
    private String gameId;
    private String playerId;
    private int score;
    private int stage;
    private int enemiesKilled;
    private float gameTime;
    private long startTime;
    private long timestamp;
    private int frameCount;
    private List<InputFrame> inputSequence;
    private byte[] rawBinaryData;
    private boolean verified;
    private String checksum;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InputFrame implements Serializable {
        private int frame;
        private byte input;
    }
}
