package thesis.project.gu.weather.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "weather.api")
public record WeatherApiProperties(
        String apiKey,
        String baseUrl,
        boolean enabled
) {}
