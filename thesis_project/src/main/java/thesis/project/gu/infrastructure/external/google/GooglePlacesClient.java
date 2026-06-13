package thesis.project.gu.infrastructure.external.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import thesis.project.gu.infrastructure.external.google.GooglePlacesProperties;
import thesis.project.gu.infrastructure.external.google.cache.GooglePlacesTextSearchCacheMapper;
import thesis.project.gu.infrastructure.external.google.cache.GooglePlacesTextSearchCache;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

@Component
public class GooglePlacesClient {
    private static final Logger log = LoggerFactory.getLogger(GooglePlacesClient.class);
    private static final int MAX_CONCURRENT_TEXT_SEARCH = 3;

    private final GooglePlacesProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final GooglePlacesTextSearchCacheMapper cacheMapper;
    private final Semaphore textSearchSemaphore = new Semaphore(MAX_CONCURRENT_TEXT_SEARCH);

    public GooglePlacesClient(
            GooglePlacesProperties properties,
            ObjectMapper objectMapper,
            GooglePlacesTextSearchCacheMapper cacheMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.cacheMapper = cacheMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public boolean isEnabled() {
        return properties.enabled() && properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    @Cacheable(
            cacheNames = "google_places_text_search",
            key = "(#textQuery == null ? '' : #textQuery.trim().toLowerCase()) + ':' + (#city == null ? '' : #city.trim().toLowerCase())",
            unless = "#result == null || #result.isEmpty()"
    )
    @CircuitBreaker(name = "googlePlaces", fallbackMethod = "searchTextFallback")
    @RateLimiter(name = "googlePlaces", fallbackMethod = "searchTextFallback")
    public List<PlaceCandidate> searchText(String textQuery, String city) {
        if (!isEnabled()) return List.of();
        try {
            String query = city == null || city.isBlank() ? textQuery : textQuery + ", " + city;
            String cacheKey = buildCacheKey(textQuery, city);
            List<PlaceCandidate> cached = readDbCache(cacheKey);
            if (!cached.isEmpty()) {
                return cached;
            }

            textSearchSemaphore.acquire();
            try {
            String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("textQuery", query)
                    .put("languageCode", "en")
                    .put("regionCode", "AU")
                    .put("maxResultCount", 5));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create((properties.baseUrl() == null || properties.baseUrl().isBlank()
                            ? "https://places.googleapis.com/v1"
                            : properties.baseUrl().trim()) + "/places:searchText"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Api-Key", properties.apiKey().trim())
                    .header("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.businessStatus,places.types,places.googleMapsUri,places.location")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Google Places search failed status={} body={}", response.statusCode(), response.body());
                throw new IllegalStateException("Google Places HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode places = root.path("places");
            if (!places.isArray()) return List.of();

            List<PlaceCandidate> result = new ArrayList<>();
            for (JsonNode place : places) {
                String name = place.path("displayName").path("text").asText("");
                String formattedAddress = place.path("formattedAddress").asText("");
                String businessStatus = place.path("businessStatus").asText("");
                String websiteUri = place.path("websiteUri").asText("");
                String googleMapsUri = place.path("googleMapsUri").asText("");
                double lat = place.path("location").path("latitude").asDouble(Double.NaN);
                double lng = place.path("location").path("longitude").asDouble(Double.NaN);

                List<String> types = new ArrayList<>();
                JsonNode typesNode = place.path("types");
                if (typesNode.isArray()) {
                    for (JsonNode typeNode : typesNode) {
                        types.add(typeNode.asText(""));
                    }
                }

                result.add(new PlaceCandidate(name, formattedAddress, businessStatus, types, websiteUri, googleMapsUri, false, lat, lng));
            }
            writeDbCache(cacheKey, textQuery, city, result);
            return result;
            } finally {
                textSearchSemaphore.release();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Google Places search error", e);
        }
    }

    private List<PlaceCandidate> searchTextFallback(String textQuery, String city, Throwable cause) {
        log.warn("Google Places search degraded query={} city={} reason={}", textQuery, city, cause.toString());
        return List.of();
    }

    private String buildCacheKey(String textQuery, String city) {
        String normalizedQuery = textQuery == null ? "" : textQuery.trim().toLowerCase(Locale.ROOT);
        String normalizedCity = city == null ? "" : city.trim().toLowerCase(Locale.ROOT);
        return normalizedQuery + ":" + normalizedCity;
    }

    private List<PlaceCandidate> readDbCache(String cacheKey) {
        try {
            GooglePlacesTextSearchCache cache = cacheMapper.selectByKey(cacheKey);
            if (cache == null || cache.getResponseJson() == null || cache.getResponseJson().isBlank()) {
                return List.of();
            }
            return objectMapper.readerForListOf(PlaceCandidate.class).readValue(cache.getResponseJson());
        } catch (Exception e) {
            log.debug("Google Places DB cache read skipped", e);
            return List.of();
        }
    }

    private void writeDbCache(String cacheKey, String textQuery, String city, List<PlaceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        try {
            GooglePlacesTextSearchCache cache = new GooglePlacesTextSearchCache();
            cache.setCacheKey(cacheKey);
            cache.setTextQuery(textQuery == null ? "" : textQuery);
            cache.setCity(city);
            cache.setResponseJson(objectMapper.writeValueAsString(candidates));
            cacheMapper.upsert(cache);
        } catch (Exception e) {
            log.debug("Google Places DB cache write skipped", e);
        }
    }

    public record PlaceCandidate(
            String name,
            String formattedAddress,
            String businessStatus,
            List<String> types,
            String websiteUri,
            String googleMapsUri,
            boolean openNow,
            double lat,
            double lng
    ) {}
}
