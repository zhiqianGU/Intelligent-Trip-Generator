package thesis.project.gu.infrastructure.external.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.geocoding")
public record GoogleGeocodingProperties(
        String apiKey,
        String baseUrl,
        boolean enabled
) {}
