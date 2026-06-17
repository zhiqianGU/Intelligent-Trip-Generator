package thesis.project.gu.planning.domain;

import thesis.project.gu.catalog.local.LocalPoiCatalog;

import java.util.List;

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

    private static List<PlaceCandidate> toCandidates(List<thesis.project.gu.catalog.local.LocalPoiItem> items, PlaceCandidateType fallbackType) {
        return items == null ? List.of() : items.stream()
                .map(item -> PlaceCandidate.fromLocalPoiItem(item, fallbackType))
                .toList();
    }

    private static int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
