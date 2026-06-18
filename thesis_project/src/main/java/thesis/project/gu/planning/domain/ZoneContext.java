package thesis.project.gu.planning.domain;

import java.util.List;

public record ZoneContext(
        String zoneId,
        String name,
        String zoneType,
        List<String> themes,
        AvailablePoiCounts availablePoiCounts,
        MealSupport mealSupport,
        int capacity,
        String weatherSuitability,
        String recommendedAllocation,
        String dataFreshness,
        String semanticProfile,
        String snapshotVersion
) {
    public ZoneContext {
        themes = themes == null ? List.of() : List.copyOf(themes);
        availablePoiCounts = availablePoiCounts == null ? new AvailablePoiCounts(0, 0, 0) : availablePoiCounts;
        mealSupport = mealSupport == null ? new MealSupport(0, 0) : mealSupport;
        weatherSuitability = weatherSuitability == null ? "" : weatherSuitability;
        recommendedAllocation = recommendedAllocation == null ? "" : recommendedAllocation;
        dataFreshness = dataFreshness == null ? "" : dataFreshness;
        semanticProfile = semanticProfile == null ? "" : semanticProfile;
        snapshotVersion = snapshotVersion == null ? "" : snapshotVersion;
    }

    public record AvailablePoiCounts(
            int activities,
            int indoorActivities,
            int familyFriendlyActivities
    ) {
    }

    public record MealSupport(
            int lunchOptions,
            int dinnerOptions
    ) {
    }
}
