package app.alertify.ai.chat;

import java.util.List;

record AiTokenUsageValue(long inputTokens, long cachedInputTokens, long cacheWriteInputTokens, long outputTokens,
        long reasoningOutputTokens, long totalTokens) {

    static final AiTokenUsageValue ZERO = new AiTokenUsageValue(0, 0, 0, 0, 0, 0);

    AiTokenUsageValue plus(AiTokenUsageValue other) {
        return new AiTokenUsageValue(Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(cachedInputTokens, other.cachedInputTokens),
                Math.addExact(cacheWriteInputTokens, other.cacheWriteInputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(reasoningOutputTokens, other.reasoningOutputTokens),
                Math.addExact(totalTokens, other.totalTokens));
    }
}

record AiRateLimitWindowValue(String key, String label, Double usedPercent, Double remainingPercent, Long limit,
        Long remaining, Long windowMinutes, Long resetsAtEpochSeconds) {
}

record AiQuotaSnapshotValue(String planType, List<AiRateLimitWindowValue> windows, long observedAtEpochSeconds) {

    AiQuotaSnapshotValue {
        windows = List.copyOf(windows);
    }
}

record AiTurnUsageUpdate(AiTokenUsageValue request, long modelContextWindow, AiQuotaSnapshotValue quota) {
}
