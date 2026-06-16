package com.retroshooter.service;

import com.retroshooter.entity.GameRecord;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class BinarySerializer {
    public static final int MAGIC_NUMBER = 0x5253;
    public static final byte VERSION = 1;
    public static final int HEADER_SIZE = 4;

    public static class DeserializationResult {
        private final boolean success;
        private final String errorMessage;
        private final GameRecord record;

        private DeserializationResult(boolean success, String errorMessage, GameRecord record) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.record = record;
        }

        public static DeserializationResult success(GameRecord record) {
            return new DeserializationResult(true, null, record);
        }

        public static DeserializationResult error(String message) {
            return new DeserializationResult(false, message, null);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public GameRecord getRecord() { return record; }
    }

    public DeserializationResult deserialize(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            return DeserializationResult.error("Data too short or null");
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int magic = in.readUnsignedShort();
            if (magic != MAGIC_NUMBER) {
                return DeserializationResult.error("Invalid magic number: " + magic);
            }

            byte version = in.readByte();
            if (version != VERSION) {
                return DeserializationResult.error("Unsupported version: " + version);
            }

            in.readByte();

            int gameIdLen = in.readUnsignedByte();
            byte[] gameIdBytes = new byte[gameIdLen];
            in.readFully(gameIdBytes);
            String gameId = new String(gameIdBytes, StandardCharsets.UTF_8);

            int playerIdLen = in.readUnsignedByte();
            byte[] playerIdBytes = new byte[playerIdLen];
            in.readFully(playerIdBytes);
            String playerId = new String(playerIdBytes, StandardCharsets.UTF_8);

            int score = in.readInt();
            int stage = in.readUnsignedShort();
            long enemiesKilled = in.readInt() & 0xFFFFFFFFL;
            float gameTime = in.readFloat();
            long startTime = in.readLong();
            int frameCount = in.readInt();

            int inputCount = in.readInt();
            if (inputCount < 0 || inputCount > 100000) {
                return DeserializationResult.error("Invalid input count: " + inputCount);
            }

            List<GameRecord.InputFrame> inputSequence = new ArrayList<>(inputCount);
            for (int i = 0; i < inputCount; i++) {
                int frame = in.readInt();
                byte input = in.readByte();
                inputSequence.add(GameRecord.InputFrame.builder()
                        .frame(frame)
                        .input(input)
                        .build());
            }

            GameRecord record = GameRecord.builder()
                    .gameId(gameId)
                    .playerId(playerId)
                    .score(score)
                    .stage(stage)
                    .enemiesKilled((int) enemiesKilled)
                    .gameTime(gameTime)
                    .startTime(startTime)
                    .timestamp(System.currentTimeMillis())
                    .frameCount(frameCount)
                    .inputSequence(inputSequence)
                    .rawBinaryData(data)
                    .checksum(calculateChecksum(data))
                    .build();

            return DeserializationResult.success(record);
        } catch (IOException e) {
            return DeserializationResult.error("Deserialization failed: " + e.getMessage());
        }
    }

    public byte[] serializeForStorage(GameRecord record) {
        List<GameRecord.InputFrame> inputSequence = record.getInputSequence();
        int inputCount = inputSequence.size();
        
        byte[] gameIdBytes = record.getGameId().getBytes(StandardCharsets.UTF_8);
        byte[] playerIdBytes = record.getPlayerId().getBytes(StandardCharsets.UTF_8);
        
        int totalSize = HEADER_SIZE + 
                       1 + gameIdBytes.length + 
                       1 + playerIdBytes.length +
                       4 + 2 + 4 + 4 + 8 + 4 +
                       4 + inputCount * 5;
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        buffer.putShort((short) MAGIC_NUMBER);
        buffer.put(VERSION);
        buffer.put((byte) 0);
        
        buffer.put((byte) gameIdBytes.length);
        buffer.put(gameIdBytes);
        
        buffer.put((byte) playerIdBytes.length);
        buffer.put(playerIdBytes);
        
        buffer.putInt(record.getScore());
        buffer.putShort((short) record.getStage());
        buffer.putInt(record.getEnemiesKilled());
        buffer.putFloat(record.getGameTime());
        buffer.putLong(record.getStartTime());
        buffer.putInt(record.getFrameCount());
        
        buffer.putInt(inputCount);
        for (GameRecord.InputFrame frame : inputSequence) {
            buffer.putInt(frame.getFrame());
            buffer.put(frame.getInput());
        }
        
        return buffer.array();
    }

    private String calculateChecksum(byte[] data) {
        int checksum = 0;
        for (byte b : data) {
            checksum = (checksum + (b & 0xFF)) % 256;
        }
        return Integer.toHexString(checksum);
    }

    public String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
