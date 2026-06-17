package thesis.project.gu.catalog.domain;

import java.util.Map;

public record ZoneCapabilitySummary(
        int attractionCount,
        int restaurantCount,
        int hotelCount,
        int lunchRestaurantCount,
        int dinnerRestaurantCount,
        int familyFriendlyCount,
        Map<String, Integer> categoryCounts,
        Map<String, Integer> styleTagCounts
) {
    public ZoneCapabilitySummary {
        categoryCounts = categoryCounts == null ? Map.of() : Map.copyOf(categoryCounts);
        styleTagCounts = styleTagCounts == null ? Map.of() : Map.copyOf(styleTagCounts);
    }

    public int relaxedDayCapacity() {
        return attractionCount / 2;
    }

    public int normalDayCapacity() {
        return attractionCount / 3;
    }
}
