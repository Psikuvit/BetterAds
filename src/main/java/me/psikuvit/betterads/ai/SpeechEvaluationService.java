package me.psikuvit.betterads.ai;

public interface SpeechEvaluationService {
    /**
     * Evaluate speech quality / clarity. Returns score between 0.0 and 1.0
     */
    double evaluate(String storageKey);
}
