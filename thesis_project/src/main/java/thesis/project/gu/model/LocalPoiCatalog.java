package thesis.project.gu.model;

import java.util.List;

public record LocalPoiCatalog(
        String city,
        String country,
        String state,
        String currency,
        List<LocalPoiItem> hotels,
        List<LocalPoiItem> attractions,
        List<LocalPoiItem> restaurants
) {
    public int totalItemCount() {
        return safeSize(hotels) + safeSize(attractions) + safeSize(restaurants);
    }

    private static int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
