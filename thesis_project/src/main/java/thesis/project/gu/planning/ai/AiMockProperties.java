package thesis.project.gu.planning.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiMockProperties(
        Boolean mockEnabled,
        Boolean mockPolishEnabled,
        String mockRawPath,
        String mockRetryRawPath
) {
    public boolean isMockEnabled() {
        return mockEnabled != null && mockEnabled;
    }

    public boolean isMockPolishEnabled() {
        return mockPolishEnabled != null && mockPolishEnabled;
    }

    public String resolvedMockRawPath() {
        return mockRawPath == null || mockRawPath.isBlank()
                ? "perf/mock-draft-raw.json"
                : mockRawPath.trim();
    }

    public String resolvedMockRetryRawPath() {
        return mockRetryRawPath == null || mockRetryRawPath.isBlank()
                ? "perf/mock-draft-retry-raw.json"
                : mockRetryRawPath.trim();
    }
}
