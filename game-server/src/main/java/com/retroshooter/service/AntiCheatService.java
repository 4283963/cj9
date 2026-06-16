package com.retroshooter.service;

import com.retroshooter.entity.GameRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class AntiCheatService {
    private static final int KEY_UP = 1 << 0;
    private static final int KEY_DOWN = 1 << 1;
    private static final int KEY_LEFT = 1 << 2;
    private static final int KEY_RIGHT = 1 << 3;
    private static final int KEY_FIRE = 1 << 4;

    private static final float PLAYER_SPEED = 5.0f;
    private static final int MAX_STAGE = 5;
    private static final int STAGE_BOSS_SCORE = 5000;
    private static final int BOSS_SCORE = 5000;
    private static final int NORMAL_ENEMY_SCORE = 100;
    private static final int FAST_ENEMY_SCORE = 150;
    private static final int HEAVY_ENEMY_SCORE = 300;

    @Value("${game.config.max-score:1000000}")
    private int maxScore;

    @Value("${game.config.max-game-time-seconds:3600}")
    private int maxGameTime;

    @Value("${game.config.min-input-entries:100}")
    private int minInputEntries;

    @Value("${game.config.anti-cheat.max-impossible-score-deviation:0.3}")
    private double maxScoreDeviation;

    @Value("${game.config.anti-cheat.min-frame-interval:15}")
    private int minFrameInterval;

    public static class VerificationResult {
        private final boolean passed;
        private final String details;
        private final int simulatedScore;
        private final List<String> violations;

        private VerificationResult(boolean passed, String details, int simulatedScore, List<String> violations) {
            this.passed = passed;
            this.details = details;
            this.simulatedScore = simulatedScore;
            this.violations = violations;
        }

        public static VerificationResult pass(int simulatedScore) {
            return new VerificationResult(true, "Verification passed", simulatedScore, Collections.emptyList());
        }

        public static VerificationResult fail(String reason, int simulatedScore, List<String> violations) {
            return new VerificationResult(false, reason, simulatedScore, violations);
        }

        public boolean isPassed() { return passed; }
        public String getDetails() { return details; }
        public int getSimulatedScore() { return simulatedScore; }
        public List<String> getViolations() { return violations; }
    }

    public VerificationResult verify(GameRecord record) {
        List<String> violations = new ArrayList<>();
        
        if (!validateBasicData(record, violations)) {
            return VerificationResult.fail("Basic data validation failed", 0, violations);
        }
        
        if (!validateInputSequence(record, violations)) {
            return VerificationResult.fail("Input sequence validation failed", 0, violations);
        }
        
        SimulationResult simResult = simulateGame(record);
        int simulatedScore = simResult.score;
        
        double deviation = Math.abs(record.getScore() - simulatedScore) / (double) Math.max(simulatedScore, 1);
        if (deviation > maxScoreDeviation) {
            violations.add(String.format("Score deviation too high: claimed=%d, simulated=%d, deviation=%.2f%%",
                    record.getScore(), simulatedScore, deviation * 100));
            return VerificationResult.fail(
                    String.format("Score mismatch: claimed %d but simulation got %d", record.getScore(), simulatedScore),
                    simulatedScore, violations);
        }
        
        if (simResult.enemiesKilled < record.getEnemiesKilled() * 0.9) {
            violations.add(String.format("Enemies killed mismatch: claimed=%d, simulated=%d",
                    record.getEnemiesKilled(), simResult.enemiesKilled));
        }
        
        if (!violations.isEmpty()) {
            return VerificationResult.fail("Minor violations detected", simulatedScore, violations);
        }
        
        return VerificationResult.pass(simulatedScore);
    }

    private boolean validateBasicData(GameRecord record, List<String> violations) {
        if (record.getScore() < 0) {
            violations.add("Negative score: " + record.getScore());
            return false;
        }
        
        if (record.getScore() > maxScore) {
            violations.add("Score exceeds maximum: " + record.getScore() + " > " + maxScore);
            return false;
        }
        
        if (record.getGameTime() < 0) {
            violations.add("Negative game time: " + record.getGameTime());
            return false;
        }
        
        if (record.getGameTime() > maxGameTime) {
            violations.add("Game time exceeds maximum: " + record.getGameTime() + " > " + maxGameTime);
            return false;
        }
        
        if (record.getStage() < 1 || record.getStage() > MAX_STAGE + 1) {
            violations.add("Invalid stage: " + record.getStage());
            return false;
        }
        
        if (record.getEnemiesKilled() < 0) {
            violations.add("Negative enemies killed: " + record.getEnemiesKilled());
            return false;
        }
        
        if (record.getFrameCount() < 0) {
            violations.add("Negative frame count: " + record.getFrameCount());
            return false;
        }
        
        long currentTime = System.currentTimeMillis();
        if (record.getStartTime() > currentTime + 60000) {
            violations.add("Start time in the future: " + new Date(record.getStartTime()));
            return false;
        }
        
        if (record.getStartTime() < currentTime - 7 * 24 * 3600 * 1000L) {
            violations.add("Start time too old: " + new Date(record.getStartTime()));
            return false;
        }
        
        float expectedFrameCount = record.getGameTime() * 60.0f;
        if (Math.abs(record.getFrameCount() - expectedFrameCount) > expectedFrameCount * 0.2) {
            violations.add(String.format("Frame count suspicious: %d frames for %.1fs (expected ~%.0f)",
                    record.getFrameCount(), record.getGameTime(), expectedFrameCount));
        }
        
        int minPossibleScore = record.getEnemiesKilled() * NORMAL_ENEMY_SCORE;
        if (record.getScore() < minPossibleScore * 0.8) {
            violations.add(String.format("Score too low for kills: %d < %d (minimum expected)",
                    record.getScore(), minPossibleScore));
        }
        
        int maxPossibleScore = (int) (record.getGameTime() * 200) + record.getStage() * 2000;
        if (record.getScore() > maxPossibleScore * 1.5) {
            violations.add(String.format("Score too high for time: %d > %d (maximum expected)",
                    record.getScore(), maxPossibleScore));
        }
        
        return violations.isEmpty();
    }

    private boolean validateInputSequence(GameRecord record, List<String> violations) {
        List<GameRecord.InputFrame> inputSequence = record.getInputSequence();
        
        if (inputSequence.size() < minInputEntries) {
            violations.add("Too few input entries: " + inputSequence.size());
            return false;
        }
        
        int lastFrame = -1;
        int duplicateCount = 0;
        int gapCount = 0;
        
        for (int i = 0; i < inputSequence.size(); i++) {
            GameRecord.InputFrame frame = inputSequence.get(i);
            
            if (frame.getFrame() < 0) {
                violations.add("Negative frame number at index " + i);
                return false;
            }
            
            if (frame.getFrame() > record.getFrameCount()) {
                violations.add("Frame exceeds total: " + frame.getFrame() + " > " + record.getFrameCount());
                return false;
            }
            
            if (frame.getFrame() <= lastFrame) {
                if (frame.getFrame() == lastFrame) {
                    duplicateCount++;
                } else {
                    violations.add("Non-increasing frame number at index " + i);
                    return false;
                }
            } else if (frame.getFrame() - lastFrame > 255 && i > 0) {
                gapCount++;
            }
            
            if ((frame.getInput() & KEY_UP) != 0 && (frame.getInput() & KEY_DOWN) != 0) {
                violations.add("Impossible input: UP and DOWN pressed simultaneously at frame " + frame.getFrame());
            }
            
            if ((frame.getInput() & KEY_LEFT) != 0 && (frame.getInput() & KEY_RIGHT) != 0) {
                violations.add("Impossible input: LEFT and RIGHT pressed simultaneously at frame " + frame.getFrame());
            }
            
            int invalidBits = frame.getInput() & 0xE0;
            if (invalidBits != 0) {
                violations.add("Invalid input bits at frame " + frame.getFrame() + ": " + invalidBits);
            }
            
            lastFrame = frame.getFrame();
        }
        
        if (duplicateCount > inputSequence.size() * 0.1) {
            violations.add("Too many duplicate frames: " + duplicateCount);
        }
        
        if (gapCount > inputSequence.size() * 0.05) {
            violations.add("Too many gaps in input: " + gapCount);
        }
        
        long fireCount = inputSequence.stream()
                .filter(f -> (f.getInput() & KEY_FIRE) != 0)
                .count();
        double fireRate = fireCount / Math.max(record.getGameTime(), 0.1);
        if (fireRate > 15) {
            violations.add(String.format("Suspicious fire rate: %.2f shots/sec", fireRate));
        }
        
        int validInputs = 0;
        for (GameRecord.InputFrame frame : inputSequence) {
            if (frame.getInput() != 0) {
                validInputs++;
            }
        }
        if (validInputs < inputSequence.size() * 0.1) {
            violations.add("Too few valid inputs: " + validInputs);
        }
        
        return violations.isEmpty();
    }

    private static class SimulationResult {
        int score;
        int enemiesKilled;
        int stage;

        SimulationResult(int score, int enemiesKilled, int stage) {
            this.score = score;
            this.enemiesKilled = enemiesKilled;
            this.stage = stage;
        }
    }

    private SimulationResult simulateGame(GameRecord record) {
        List<GameRecord.InputFrame> inputSequence = record.getInputSequence();
        
        int score = 0;
        int enemiesKilled = 0;
        int stage = 1;
        int frame = 0;
        int inputIndex = 0;
        byte currentInput = 0;
        
        int spawnTimer = 0;
        boolean bossActive = false;
        
        int consecutiveFireFrames = 0;
        int estimatedBullets = 0;
        int estimatedHits = 0;
        
        float playerX = 200;
        float playerY = 300;
        
        int lastScoreMilestone = 0;
        
        for (frame = 0; frame < record.getFrameCount(); frame++) {
            while (inputIndex < inputSequence.size() && 
                   inputSequence.get(inputIndex).getFrame() <= frame) {
                currentInput = inputSequence.get(inputIndex).getInput();
                inputIndex++;
            }
            
            if ((currentInput & KEY_UP) != 0) playerY -= PLAYER_SPEED;
            if ((currentInput & KEY_DOWN) != 0) playerY += PLAYER_SPEED;
            if ((currentInput & KEY_LEFT) != 0) playerX -= PLAYER_SPEED;
            if ((currentInput & KEY_RIGHT) != 0) playerX += PLAYER_SPEED;
            
            playerX = Math.max(20, Math.min(780, playerX));
            playerY = Math.max(20, Math.min(580, playerY));
            
            if ((currentInput & KEY_FIRE) != 0) {
                consecutiveFireFrames++;
                if (consecutiveFireFrames % 2 == 0) {
                    estimatedBullets++;
                }
            } else {
                consecutiveFireFrames = 0;
            }
            
            spawnTimer++;
            int spawnRate = Math.max(20, 60 - stage * 8);
            
            if (!bossActive && spawnTimer >= spawnRate) {
                spawnTimer = 0;
                
                double enemyTypeRand = Math.random();
                int enemyScore;
                if (enemyTypeRand < 0.6) {
                    enemyScore = NORMAL_ENEMY_SCORE;
                } else if (enemyTypeRand < 0.85) {
                    enemyScore = FAST_ENEMY_SCORE;
                } else {
                    enemyScore = HEAVY_ENEMY_SCORE;
                }
                
                if (estimatedBullets > 0 && Math.random() < 0.25) {
                    estimatedBullets--;
                    estimatedHits++;
                    enemiesKilled++;
                    score += enemyScore;
                    
                    if (Math.random() < 0.1) {
                        score += 100;
                    }
                }
            }
            
            int nextBossScore = stage * STAGE_BOSS_SCORE;
            if (!bossActive && score >= nextBossScore && stage <= MAX_STAGE) {
                bossActive = true;
                estimatedHits += 50;
                score += BOSS_SCORE;
                enemiesKilled++;
                bossActive = false;
                stage++;
                score += 2000 * (stage - 1);
            }
            
            if (score / STAGE_BOSS_SCORE > lastScoreMilestone) {
                lastScoreMilestone = score / STAGE_BOSS_SCORE;
            }
        }
        
        double accuracy = estimatedBullets > 0 ? (double) estimatedHits / estimatedBullets : 0;
        if (accuracy > 0.8) {
            score = (int) (score * 0.9);
        }
        
        if (score > record.getScore() * 1.1) {
            score = (int) (record.getScore() * 1.05);
        }
        
        return new SimulationResult(score, enemiesKilled, stage);
    }
}
