package thesis.project.gu.planning.domain;

import thesis.project.gu.catalog.local.LocalPoiCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record PlaceCandidatePool(
        String city,
        String country,
        String state,
        String currency,
        List<PlaceCandidate> hotels,
        List<PlaceCandidate> attractions,
        List<PlaceCandidate> restaurants
) {
    public static PlaceCandidatePool fromLocalCatalog(LocalPoiCatalog catalog) {
        if (catalog == null) {
            return empty("");
        }
        return new PlaceCandidatePool(
                catalog.city(),
                catalog.country(),
                catalog.state(),
                catalog.currency(),
                toCandidates(catalog.hotels(), PlaceCandidateType.HOTEL),
                toCandidates(catalog.attractions(), PlaceCandidateType.ATTRACTION),
                toCandidates(catalog.restaurants(), PlaceCandidateType.RESTAURANT)
        );
    }

    public static PlaceCandidatePool empty(String city) {
        return new PlaceCandidatePool(city, null, null, null, List.of(), List.of(), List.of());
    }

    public LocalPoiCatalog toLocalCatalog() {
        return new LocalPoiCatalog(
                city,
                country,
                state,
                currency,
                hotels == null ? List.of() : hotels.stream().map(PlaceCandidate::toLocalPoiItem).toList(),
                attractions == null ? List.of() : attractions.stream().map(PlaceCandidate::toLocalPoiItem).toList(),
                restaurants == null ? List.of() : restaurants.stream().map(PlaceCandidate::toLocalPoiItem).toList()
        );
    }

    public int totalItemCount() {
        return safeSize(hotels) + safeSize(attractions) + safeSize(restaurants);
    }

    public PlaceCandidatePool withAdditionalCandidates(List<PlaceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return this;
        }
        List<PlaceCandidate> addedHotels = new ArrayList<>();
        List<PlaceCandidate> addedAttractions = new ArrayList<>();
        List<PlaceCandidate> addedRestaurants = new ArrayList<>();
        for (PlaceCandidate candidate : candidates) {
            if (candidate == null || candidate.type() == null || isBlank(candidate.name())) {
                continue;
            }
            switch (candidate.type()) {
                case HOTEL -> addedHotels.add(candidate);
                case ATTRACTION -> addedAttractions.add(candidate);
                case RESTAURANT -> addedRestaurants.add(candidate);
            }
        }
        return new PlaceCandidatePool(
                city,
                country,
                state,
                currency,
                mergeDeduped(hotels, addedHotels),
                mergeDeduped(attractions, addedAttractions),
                mergeDeduped(restaurants, addedRestaurants)
        );
    }

    private static List<PlaceCandidate> toCandidates(List<thesis.project.gu.catalog.local.LocalPoiItem> items, PlaceCandidateType fallbackType) {
        return items == null ? List.of() : items.stream()
                .map(item -> PlaceCandidate.fromLocalPoiItem(item, fallbackType))
                .toList();
    }

    private static int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static List<PlaceCandidate> mergeDeduped(List<PlaceCandidate> existing, List<PlaceCandidate> added) {
        Map<String, PlaceCandidate> merged = new LinkedHashMap<>();
        if (existing != null) {
            existing.stream()
                    .filter(candidate -> candidate != null && !isBlank(candidate.name()))
                    .forEach(candidate -> merged.putIfAbsent(dedupeKey(candidate), candidate));
        }
        if (added != null) {
            added.stream()
                    .filter(candidate -> candidate != null && !isBlank(candidate.name()))
                    .forEach(candidate -> merged.putIfAbsent(dedupeKey(candidate), candidate));
        }
        return List.copyOf(merged.values());
    }

    private static String dedupeKey(PlaceCandidate candidate) {
        return normalize(candidate.type() == null ? null : candidate.type().name())
                + "|" + normalize(candidate.name())
                + "|" + normalize(candidate.area())
                + "|" + normalize(candidate.addressLine());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
