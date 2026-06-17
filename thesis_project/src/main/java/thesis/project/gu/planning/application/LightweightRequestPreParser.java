package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class LightweightRequestPreParser {
    public ParsedPlanningRequest parse(CreatePlanReq req) {
        String pace = normalizedPace(req == null ? null : req.pace());
        CreatePlanReq.Party party = req == null ? null : req.party();
        int adults = nonNegative(party == null ? null : party.adults(), 2);
        int kids = nonNegative(party == null ? null : party.kids(), 0);
        List<String> preferenceHints = preferenceHints(req == null ? null : req.style());
        return new ParsedPlanningRequest(
                req == null ? null : req.city(),
                Math.max(1, req == null ? 1 : req.days()),
                Math.max(1, adults + kids),
                pace,
                req == null ? null : req.budget(),
                preferenceHints,
                List.of(),
                "relaxed".equals(pace),
                kids > 0,
                preferenceHints.contains("indoor") || preferenceHints.contains("culture"),
                null
        );
    }

    private List<String> preferenceHints(List<String> styles) {
        if (styles == null || styles.isEmpty()) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        for (String style : styles) {
            String normalized = normalize(style);
            switch (normalized) {
                case "culture", "museum", "gallery", "art" -> addUnique(hints, "culture");
                case "nature", "park", "garden", "outdoor" -> addUnique(hints, "nature");
                case "shopping", "market", "market_shopping", "market-shopping" -> addUnique(hints, "shopping");
                case "theme_park", "theme-park", "themepark" -> addUnique(hints, "theme_park");
                case "food", "dining", "restaurant", "local_food", "local-food" -> addUnique(hints, "local-dining");
                case "indoor", "rainy_day", "rainy-day" -> addUnique(hints, "indoor");
                default -> {
                    if (!normalized.isBlank()) {
                        addUnique(hints, normalized);
                    }
                }
            }
        }
        return List.copyOf(hints);
    }

    private void addUnique(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private int nonNegative(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    private String normalizedPace(String pace) {
        String normalized = normalize(pace);
        return switch (normalized) {
            case "relax", "relaxed", "slow" -> "relaxed";
            case "rush", "fast", "fast_pace", "fast-pace", "intense" -> "rush";
            default -> "normal";
        };
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_');
    }
}
