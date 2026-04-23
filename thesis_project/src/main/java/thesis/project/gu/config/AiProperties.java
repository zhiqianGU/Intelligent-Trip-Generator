package thesis.project.gu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quenmoel")
public record AiProperties(
        String key,
        String baseUrl,
        String provider,
        String model,
        String geminiKey,
        String geminiBaseUrl,
        String copyPolishProvider,
        String copyPolishModel,
        Boolean copyPolishEnabled,
        Integer copyPolishTimeoutSeconds
) {
    public String resolvedProvider() {
        return provider == null || provider.isBlank() ? "qwen" : provider.trim().toLowerCase();
    }

    public String resolvedModel() {
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return "gemini".equals(resolvedProvider()) ? "gemini-2.5-flash" : "qwen-plus";
    }

    public String resolvedCopyPolishModel() {
        return copyPolishModel == null || copyPolishModel.isBlank()
                ? ("gemini".equals(resolvedCopyPolishProvider()) ? "gemini-2.5-flash" : resolvedModel())
                : copyPolishModel.trim();
    }

    public String resolvedCopyPolishProvider() {
        return copyPolishProvider == null || copyPolishProvider.isBlank()
                ? resolvedProvider()
                : copyPolishProvider.trim().toLowerCase();
    }

    public String resolvedGeminiKey() {
        return geminiKey == null ? "" : geminiKey.trim();
    }

    public String resolvedGeminiBaseUrl() {
        return geminiBaseUrl == null || geminiBaseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com/v1beta"
                : geminiBaseUrl.trim();
    }

    public boolean isCopyPolishEnabled() {
        return copyPolishEnabled == null || copyPolishEnabled;
    }

    public int resolvedCopyPolishTimeoutSeconds() {
        return copyPolishTimeoutSeconds == null || copyPolishTimeoutSeconds <= 0 ? 6 : copyPolishTimeoutSeconds;
    }
}
