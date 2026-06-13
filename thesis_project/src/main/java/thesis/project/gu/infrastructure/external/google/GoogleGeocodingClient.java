package thesis.project.gu.infrastructure.external.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.util.UriComponentsBuilder;
import thesis.project.gu.infrastructure.external.google.GoogleGeocodingProperties;
import thesis.project.gu.infrastructure.external.google.GooglePlacesProperties;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class GoogleGeocodingClient {
    private static final Logger log = LoggerFactory.getLogger(GoogleGeocodingClient.class);
    private static final String FIELD_MASK = "results.formattedAddress,results.location,results.placeId,results.granularity";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final GoogleGeocodingProperties geocodingProperties;
    private final GooglePlacesProperties placesProperties;

    public GoogleGeocodingClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            GoogleGeocodingProperties geocodingProperties,
            GooglePlacesProperties placesProperties
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.geocodingProperties = geocodingProperties;
        this.placesProperties = placesProperties;
    }

    @CircuitBreaker(name = "googleGeocoding", fallbackMethod = "geocodeFallback")
    @RateLimiter(name = "googleGeocoding", fallbackMethod = "geocodeFallback")
    public GeoResponse geocode(String query, String city, String source) {
        if (!geocodingProperties.enabled()) {
            return null;
        }
        String key = firstNonBlank(geocodingProperties.apiKey(), placesProperties.apiKey());
        if (key == null) {
            log.debug("Google Geocoding skipped because apiKey is blank");
            return null;
        }

        String text = firstNonBlank(query, "");
        if (city != null && !city.isBlank() && !text.toLowerCase().contains(city.trim().toLowerCase())) {
            text = text + ", " + city.trim();
        }
        String baseUrl = firstNonBlank(geocodingProperties.baseUrl(), "https://geocode.googleapis.com/v4beta");
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("geocode", "address", text)
                .queryParam("key", key)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Goog-FieldMask", FIELD_MASK);

        try {
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.debug("Google Geocoding failed status={} query={}", response.getStatusCodeValue(), text);
                throw new IllegalStateException("Google Geocoding HTTP " + response.getStatusCodeValue());
            }
            return toGeoResponse(objectMapper.readTree(response.getBody()), text, city, source);
        } catch (HttpStatusCodeException e) {
            log.debug("Google Geocoding failed status={} query={} bodySnippet={}",
                    e.getStatusCode().value(), text, snippet(e.getResponseBodyAsString()));
            throw e;
        } catch (RestClientException | IOException e) {
            log.debug("Google Geocoding failed query={} reason={}", text, e.getMessage());
            throw new IllegalStateException("Google Geocoding failed", e);
        }
    }

    private GeoResponse geocodeFallback(String query, String city, String source, Throwable cause) {
        log.debug("Google Geocoding degraded query={} city={} source={} reason={}", query, city, source, cause.toString());
        return null;
    }

    private GeoResponse toGeoResponse(JsonNode root, String query, String city, String source) {
        JsonNode results = root == null ? null : root.path("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return null;
        }
        JsonNode result = results.get(0);
        JsonNode location = result.path("location");
        if (!location.path("latitude").isNumber() || !location.path("longitude").isNumber()) {
            return null;
        }

        double latitude = round6(location.path("latitude").asDouble());
        double longitude = round6(location.path("longitude").asDouble());
        String formattedAddress = firstNonBlank(result.path("formattedAddress").asText(null), query);
        String placeId = firstNonBlank(result.path("placeId").asText(null), "google-geocoding:" + query.toLowerCase());

        return new GeoResponse("FeatureCollection", List.of(new GeoResponse.Feature(
                "Feature",
                new GeoResponse.Geometry("Point", List.of(longitude, latitude)),
                new GeoResponse.Properties(
                        formattedAddress,
                        null,
                        null,
                        null,
                        city,
                        null,
                        null,
                        null,
                        latitude,
                        longitude,
                        source,
                        result.path("granularity").asText(null),
                        placeId
                )
        )));
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static String snippet(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240) + "...";
    }
}
