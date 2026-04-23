package thesis.project.gu.util;

import thesis.project.gu.req.CreatePlanReq;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class PlanningContext {
    private final String pace;
    private final StyleQuota styleQuota;
    private final MealPolicy mealPolicy;
    private final RoutePolicy routePolicy;
    private final boolean themeParkSelected;
    private final Set<String> selectedStyles;

    private PlanningContext(
            String pace,
            StyleQuota styleQuota,
            MealPolicy mealPolicy,
            RoutePolicy routePolicy,
            boolean themeParkSelected,
            Set<String> selectedStyles
    ) {
        this.pace = pace;
        this.styleQuota = styleQuota;
        this.mealPolicy = mealPolicy;
        this.routePolicy = routePolicy;
        this.themeParkSelected = themeParkSelected;
        this.selectedStyles = selectedStyles == null ? Set.of() : Set.copyOf(selectedStyles);
    }

    public static PlanningContext from(CreatePlanReq req) {
        String pace = normalizedPace(req);
        Set<String> styles = normalizedStyles(req);
        StyleQuota quota = styleQuota(req, pace);
        return new PlanningContext(
                pace,
                quota,
                new MealPolicy("11:00", "13:00", "17:30", "19:30"),
                new RoutePolicy(1, 60, 75, 120),
                quota.themeParkSelected(),
                styles
        );
    }

    public String promptPolicy() {
        String marketRule = styleQuota.marketShoppingWeight() > 0
                ? "If market_shopping fits the city and route, include at most one market/shopping non-meal stop per day, preferably afternoon or sunset, and keep it close to that day's main area."
                : "Do not add shopping malls, shopping streets, retail arcades, outlets, markets, or shopping precincts as non-meal stops.";
        String themeParkRule = themeParkSelected
                ? themeParkRule(pace)
                : "Do not add theme park stops by default.";
        String sunsetRule = "relaxed".equals(pace)
                ? "For relaxed pace, do not add a formal sunset/landmark filler stop. If useful, mention a short optional same-area sunset idea in eveningNote or tip only."
                : "For moderate or fast pace, you may include at most one short landmark/sunset stop per day, 30-45 minutes, only if it is real, in the same area or directly on the route, and does not cause backtracking.";
        String singleStyleRule = singleStyleRule();

        return """
                - Planning context:
                  pace=%s
                  targetNonMealStopsPerDay=%s
                  targetTotalNonMealStops=%s
                  styleWeights={nature:%d,culture:%d,marketShopping:%d,themePark:%s}
                  mealPolicy={lunch:%s-%s,dinner:%s-%s}
                  routePolicy={mainAreaPerDay:1,nearbySecondaryAreasMax:%d,generalGapMax:%d,lunchToAfternoonGapMax:%d,afternoonToDinnerGapMax:%d}
                - Style mix: %s.
                - Before choosing concrete places, internally create a day skeleton with: primaryArea, nearbySecondaryArea, mainTheme, rough targetCounts, lunchIntent, and dinnerIntent. Do not output these extra skeleton fields; use them to choose coherent stops in the required JSON schema.
                - Each day's lunchIntent and dinnerIntent must use the day skeleton's area and current route flow, not a random citywide restaurant area.
                - Market/shopping rule: %s
                - Theme park rule: %s
                - Single-style handling: %s
                - Short landmark/sunset rule: %s
                - Keep the ratio flexible. Realistic timing, real venues, and route compactness are more important than exact counts.
                """.formatted(
                pace,
                styleQuota.nonMealRange(),
                styleQuota.totalNonMealRange(),
                styleQuota.natureWeight(),
                styleQuota.cultureWeight(),
                styleQuota.marketShoppingWeight(),
                styleQuota.themeParkSelected(),
                mealPolicy.lunchEarliest(),
                mealPolicy.lunchLatest(),
                mealPolicy.dinnerEarliest(),
                mealPolicy.dinnerLatest(),
                routePolicy.nearbySecondaryAreasMax(),
                routePolicy.generalGapMaxMinutes(),
                routePolicy.lunchToAfternoonGapMaxMinutes(),
                routePolicy.afternoonToDinnerGapMaxMinutes(),
                styleQuota.mixRule(),
                marketRule,
                themeParkRule,
                singleStyleRule,
                sunsetRule
        ).trim();
    }

    private static StyleQuota styleQuota(CreatePlanReq req, String pace) {
        Set<String> styles = normalizedStyles(req);
        int days = Math.max(1, req.days());
        int perDayMin;
        int perDayMax;
        switch (pace) {
            case "relaxed" -> {
                perDayMin = 2;
                perDayMax = 3;
            }
            case "rush", "fast" -> {
                perDayMin = 4;
                perDayMax = 5;
            }
            default -> {
                perDayMin = 3;
                perDayMax = 4;
            }
        }

        boolean nature = styles.contains("nature");
        boolean culture = styles.contains("culture");
        boolean marketShopping = styles.contains("market_shopping");
        boolean themePark = styles.contains("theme_park");

        int marketWeight = marketShopping ? 15 : 0;
        int remaining = 100 - marketWeight;
        int natureWeight;
        int cultureWeight;
        if (nature && !culture) {
            natureWeight = Math.round(remaining * 0.70f);
            cultureWeight = remaining - natureWeight;
        } else if (culture && !nature) {
            cultureWeight = Math.round(remaining * 0.70f);
            natureWeight = remaining - cultureWeight;
        } else {
            natureWeight = remaining / 2;
            cultureWeight = remaining - natureWeight;
        }

        String mixRule = "aim for nature/parks/gardens/lookouts at about " + natureWeight
                + "%, culture/museums/galleries/heritage at about " + cultureWeight
                + "%, and market/shopping at about " + marketWeight + "%";
        return new StyleQuota(
                natureWeight,
                cultureWeight,
                marketWeight,
                themePark,
                perDayMin + " to " + perDayMax + " non-meal attraction stops per day",
                (perDayMin * days) + " to " + (perDayMax * days),
                mixRule
        );
    }

    private static String normalizedPace(CreatePlanReq req) {
        return (req.pace() == null || req.pace().isBlank()) ? "normal" : req.pace().trim().toLowerCase(Locale.ROOT);
    }

    private static String themeParkRule(String pace) {
        String dayTripPattern = switch (pace) {
            case "relaxed" -> "For relaxed pace, make the theme park day a simple day trip: one theme_park stop only, lunch in or near that remote cluster, and dinner either back in the hotel city or near the same cluster.";
            case "rush", "fast" -> "For fast pace, the theme park day may include up to two short real nearby stops plus one theme_park stop, but all non-meal stops must stay in the same remote cluster.";
            default -> "For moderate pace, the theme park day may include one real nearby stop of any suitable type plus one theme_park stop, but both must stay in the same remote cluster.";
        };
        return "If theme_park fits, include at most one theme park-style stop in the whole trip for 1-3 day trips, or at most two for 4+ day trips. "
                + "A theme park may be in a nearby city or remote suburb only if it is a genuinely practical day trip from the requested destination and a real Google Maps-verifiable POI; never invent local branches or use a distant interstate theme park. "
                + "The theme park must be the main daytime anchor of its day; do not place a full museum, garden, and city lunch block before it. "
                + "Allowed long-transfer patterns are morning out + afternoon return, midday out + evening return, or morning out + evening return only. "
                + dayTripPattern;
    }

    private String singleStyleRule() {
        if (selectedStyles.size() != 1) {
            return "When several styles are selected, use the style mix as a soft guide after pace and routing constraints.";
        }
        if (selectedStyles.contains("theme_park")) {
            return "When theme_park is the only selected style and the trip has extra non-theme-park days, make those days family-friendly, entertainment-oriented, or light iconic city days. Do not force a museum/garden balance as if culture or nature were selected.";
        }
        if (selectedStyles.contains("market_shopping")) {
            return "When market_shopping is the only selected style, anchor at most one market/shopping stop per day and fill the rest with nearby light attractions and meals rather than unrelated museums.";
        }
        return "When only one ordinary style is selected, lean clearly toward that style but keep a small amount of nearby variety if it improves timing and route compactness.";
    }

    private static Set<String> normalizedStyles(CreatePlanReq req) {
        Set<String> values = new HashSet<>();
        if (req.style() == null) {
            return values;
        }
        for (String item : req.style()) {
            if (item == null || item.isBlank()) {
                continue;
            }
            values.add(item.trim().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private record StyleQuota(
            int natureWeight,
            int cultureWeight,
            int marketShoppingWeight,
            boolean themeParkSelected,
            String nonMealRange,
            String totalNonMealRange,
            String mixRule
    ) {}

    private record MealPolicy(
            String lunchEarliest,
            String lunchLatest,
            String dinnerEarliest,
            String dinnerLatest
    ) {}

    private record RoutePolicy(
            int nearbySecondaryAreasMax,
            int generalGapMaxMinutes,
            int lunchToAfternoonGapMaxMinutes,
            int afternoonToDinnerGapMaxMinutes
    ) {}
}
