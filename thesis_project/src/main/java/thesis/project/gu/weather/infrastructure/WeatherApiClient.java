package thesis.project.gu.weather.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import thesis.project.gu.weather.infrastructure.WeatherApiProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class WeatherApiClient {
    private static final Logger log = LoggerFactory.getLogger(WeatherApiClient.class);

    private final WeatherApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WeatherApiClient(WeatherApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public boolean isEnabled() {
        return properties.enabled() && properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    @Cacheable(
            cacheNames = "weather_forecast",
            key = "(#city == null ? '' : #city.trim().toLowerCase()) + ':' + #departureDate + ':' + #tripDays",
            unless = "#result == null || #result.isEmpty()"
    )
    @CircuitBreaker(name = "weatherApi", fallbackMethod = "forecastFallback")
    @RateLimiter(name = "weatherApi", fallbackMethod = "forecastFallback")
    public Forecast forecast(String city, String departureDate, int tripDays) {
        if (!isEnabled() || city == null || city.isBlank() || departureDate == null || departureDate.isBlank()) {
            return Forecast.empty();
        }

        LocalDate departure;
        try {
            departure = LocalDate.parse(departureDate.trim());
        } catch (RuntimeException e) {
            return Forecast.empty();
        }

        LocalDate today = LocalDate.now();
        LocalDate endDate = departure.plusDays(Math.max(1, tripDays) - 1L);
        long daysFromToday = ChronoUnit.DAYS.between(today, endDate) + 1;
        if (daysFromToday < 1) {
            return Forecast.empty();
        }

        int requestDays = (int) Math.min(14, Math.max(1, daysFromToday));
        try {
            String baseUrl = properties.baseUrl() == null || properties.baseUrl().isBlank()
                    ? "https://api.weatherapi.com/v1"
                    : properties.baseUrl().trim();
            String uri = baseUrl + "/forecast.json"
                    + "?key=" + encode(properties.apiKey().trim())
                    + "&q=" + encode(city.trim())
                    + "&days=" + requestDays
                    + "&aqi=no&alerts=no";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Weather forecast failed status={} body={}", response.statusCode(), response.body());
                throw new IllegalStateException("Weather API HTTP " + response.statusCode());
            }
            return parseForecast(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Weather forecast error", e);
        }
    }

    private Forecast forecastFallback(String city, String departureDate, int tripDays, Throwable cause) {
        log.warn("Weather forecast degraded city={} departureDate={} days={} reason={}",
                city, departureDate, tripDays, cause.toString());
        return Forecast.empty();
    }

    private Forecast parseForecast(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode forecastDays = root.path("forecast").path("forecastday");
        if (!forecastDays.isArray()) {
            return Forecast.empty();
        }

        Map<String, WeatherDay> days = new HashMap<>();
        for (JsonNode forecastDay : forecastDays) {
            LocalDate date = LocalDate.parse(forecastDay.path("date").asText());
            JsonNode dayNode = forecastDay.path("day");
            WeatherDay weatherDay = new WeatherDay(
                    dayNode.path("daily_chance_of_rain").asInt(0),
                    dayNode.path("avgtemp_c").isNumber() ? dayNode.path("avgtemp_c").asDouble() : null,
                    dayNode.path("maxtemp_c").isNumber() ? dayNode.path("maxtemp_c").asDouble() : null,
                    dayNode.path("mintemp_c").isNumber() ? dayNode.path("mintemp_c").asDouble() : null,
                    dayNode.path("condition").path("text").asText(""),
                    new HashMap<>()
            );

            JsonNode hours = forecastDay.path("hour");
            if (hours.isArray()) {
                for (JsonNode hourNode : hours) {
                    String timeText = hourNode.path("time").asText("");
                    if (timeText.length() < 16) {
                        continue;
                    }
                    LocalTime hour = LocalDateTime.parse(timeText.replace(" ", "T")).toLocalTime();
                    weatherDay.hours().put(hour.getHour(), new WeatherHour(
                            hourNode.path("chance_of_rain").asInt(0),
                            hourNode.path("will_it_rain").asInt(0) == 1,
                            hourNode.path("condition").path("text").asText("")
                    ));
                }
            }
            days.put(date.toString(), weatherDay);
        }
        return new Forecast(days);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Forecast(Map<String, WeatherDay> days) {
        public static Forecast empty() {
            return new Forecast(Map.of());
        }

        @JsonIgnore
        public boolean isEmpty() {
            return days == null || days.isEmpty();
        }

        public boolean rainyAt(LocalDate date, LocalTime time) {
            if (date == null || days == null) {
                return false;
            }
            WeatherDay day = days.get(date.toString());
            if (day == null) {
                return false;
            }
            if (time != null && day.hours() != null && !day.hours().isEmpty()) {
                WeatherHour hour = day.hours().get(time.getHour());
                if (hour != null) {
                    return hour.willRain() || hour.chanceOfRain() >= 50 || looksRainy(hour.condition());
                }
            }
            return day.dailyChanceOfRain() >= 50 || looksRainy(day.condition());
        }

        private static boolean looksRainy(String condition) {
            if (condition == null) {
                return false;
            }
            String text = condition.toLowerCase(Locale.ROOT);
            return text.contains("rain")
                    || text.contains("shower")
                    || text.contains("drizzle")
                    || text.contains("storm")
                    || text.contains("thunder");
        }
    }

    public record WeatherDay(
            int dailyChanceOfRain,
            Double avgTempC,
            Double maxTempC,
            Double minTempC,
            String condition,
            Map<Integer, WeatherHour> hours
    ) {}

    public record WeatherHour(int chanceOfRain, boolean willRain, String condition) {}
}
