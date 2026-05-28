package thesis.project.gu.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import thesis.project.gu.model.LocalPoiCatalog;
import thesis.project.gu.model.LocalPoiItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocalPoiCatalogService {
    private static final String LOCAL_POI_ROOT = "local-poi/";

    private final ObjectMapper objectMapper;
    private final Map<String, LocalPoiCatalog> catalogCache = new ConcurrentHashMap<>();

    public LocalPoiCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LocalPoiCatalog catalogForCity(String city) {
        String normalizedCity = normalizeCity(city);
        if (normalizedCity.isBlank()) {
            return emptyCatalog("");
        }
        return catalogCache.computeIfAbsent(normalizedCity, this::loadCatalog);
    }

    private LocalPoiCatalog loadCatalog(String normalizedCity) {
        String city = displayCity(normalizedCity);
        try {
            LoadedItems hotels = loadItems(normalizedCity, "hotels", "hotel", city);
            LoadedItems attractions = loadItems(normalizedCity, "attractions", "attraction", city);
            LoadedItems restaurants = loadItems(normalizedCity, "restaurants", "restaurant", city);
            String catalogCity = firstNonBlank(hotels.city(), attractions.city(), restaurants.city(), city);
            String country = firstNonBlank(hotels.country(), attractions.country(), restaurants.country(), defaultCountry(catalogCity));
            String state = firstNonBlank(hotels.state(), attractions.state(), restaurants.state(), defaultState(catalogCity));
            String currency = firstNonBlank(hotels.currency(), attractions.currency(), restaurants.currency(), defaultCurrency(country));
            return new LocalPoiCatalog(
                    catalogCity,
                    country,
                    state,
                    currency,
                    hotels.items(),
                    attractions.items(),
                    restaurants.items()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load local POI catalog for " + city, e);
        }
    }

    private LoadedItems loadItems(String citySlug, String datasetType, String defaultType, String defaultCity) throws IOException {
        String filename = resolveDatasetFilename(citySlug, datasetType);
        if (filename == null) {
            return new LoadedItems(List.of(), null, null, null, null);
        }
        ClassPathResource resource = new ClassPathResource(LOCAL_POI_ROOT + filename);
        LocalPoiDataset dataset = objectMapper.readValue(resource.getInputStream(), LocalPoiDataset.class);
        if (dataset.items() != null && !dataset.items().isEmpty()) {
            return new LoadedItems(
                    normalizeItems(dataset.items(), defaultType, defaultCity, dataset.city()),
                    dataset.city(),
                    dataset.country(),
                    dataset.state(),
                    dataset.currency()
            );
        }
        if (dataset.pois() != null && !dataset.pois().isEmpty()) {
            return new LoadedItems(
                    normalizeItems(dataset.pois(), defaultType, defaultCity, dataset.city()),
                    dataset.city(),
                    dataset.country(),
                    dataset.state(),
                    dataset.currency()
            );
        }
        return new LoadedItems(List.of(), dataset.city(), dataset.country(), dataset.state(), dataset.currency());
    }

    private String resolveDatasetFilename(String citySlug, String datasetType) {
        for (String candidate : datasetFilenameCandidates(citySlug, datasetType)) {
            if (new ClassPathResource(LOCAL_POI_ROOT + candidate).exists()) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> datasetFilenameCandidates(String citySlug, String datasetType) {
        List<String> candidates = new ArrayList<>();
        candidates.add(citySlug + "_" + datasetType + ".json");
        if ("brisbane".equals(citySlug)) {
            switch (datasetType) {
                case "hotels" -> candidates.add("brisbane_hotels_8.json");
                case "attractions" -> candidates.add("brisbane_attractions_90.json");
                case "restaurants" -> candidates.add("brisbane_restaurants_72.json");
                default -> {
                }
            }
        }
        return candidates;
    }

    private List<LocalPoiItem> normalizeItems(
            List<LocalPoiItem> items,
            String defaultType,
            String defaultCity,
            String datasetCity
    ) {
        String fallbackCity = isBlank(datasetCity) ? defaultCity : datasetCity;
        return items.stream()
                .map(item -> new LocalPoiItem(
                        item.name(),
                        isBlank(item.type()) ? defaultType : item.type(),
                        item.category(),
                        isBlank(item.city()) ? fallbackCity : item.city(),
                        item.area(),
                        item.addressLine(),
                        item.latitude(),
                        item.longitude(),
                        item.stayMinutes(),
                        item.styleTags(),
                        item.timeSlots(),
                        item.priority(),
                        item.familyFriendly(),
                        item.budgetLevel(),
                        item.mealTypes(),
                        item.cuisine(),
                        item.source(),
                        item.verificationLevel()
                ))
                .toList();
    }

    private LocalPoiCatalog emptyCatalog(String city) {
        return new LocalPoiCatalog(city, defaultCountry(city), defaultState(city), defaultCurrency(defaultCountry(city)), List.of(), List.of(), List.of());
    }

    private String normalizeCity(String city) {
        return city == null
                ? ""
                : city.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String displayCity(String normalizedCity) {
        if ("brisbane".equals(normalizedCity)) {
            return "Brisbane";
        }
        if (normalizedCity == null || normalizedCity.isBlank()) {
            return "";
        }
        String[] parts = normalizedCity.split("_+");
        List<String> displayParts = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                displayParts.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
            }
        }
        return String.join(" ", displayParts);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String defaultCountry(String city) {
        return "Brisbane".equalsIgnoreCase(city) ? "Australia" : "";
    }

    private String defaultState(String city) {
        return "Brisbane".equalsIgnoreCase(city) ? "QLD" : "";
    }

    private String defaultCurrency(String country) {
        return "Australia".equalsIgnoreCase(country) ? "AUD" : "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LocalPoiDataset(
            String city,
            String country,
            String state,
            String currency,
            String datasetType,
            List<LocalPoiItem> items,
            List<LocalPoiItem> pois
    ) {}

    private record LoadedItems(
            List<LocalPoiItem> items,
            String city,
            String country,
            String state,
            String currency
    ) {}
}
