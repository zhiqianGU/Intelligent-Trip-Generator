package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;
import thesis.project.gu.planning.domain.PlanningAgentInput;
import thesis.project.gu.planning.domain.PlanningAgentOutput;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.domain.ZoneContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class LocalFallbackPlanningAgent implements PlanningAgent {
    private static final String PLANNER_TYPE = "LOCAL_FALLBACK";

    @Override
    public PlanningAgentOutput plan(PlanningAgentInput input) {
        TripPlanningSpecification specification = input == null ? null : input.specification();
        ParsedPlanningRequest parsedRequest = input == null ? null : input.parsedRequest();
        List<ZoneContext> zones = input == null ? List.of() : input.zoneContexts();
        int days = Math.max(1, specification == null ? 1 : specification.days());
        List<TripPlanningSpecification.SpecialEvent> specialEvents = specialEvents(parsedRequest);
        List<TripPlanningSpecification.DayStrategy> strategies = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            ZoneContext primary = primaryZoneForDay(day, zones, specialEvents);
            strategies.add(dayStrategy(day, primary, zones, parsedRequest, hasSpecialEvent(specialEvents, day)));
        }
        return new PlanningAgentOutput(
                specification == null ? null : specification.constraints(),
                specialEvents,
                List.copyOf(strategies),
                PLANNER_TYPE,
                true
        );
    }

    private TripPlanningSpecification.DayStrategy dayStrategy(
            int day,
            ZoneContext primary,
            List<ZoneContext> zones,
            ParsedPlanningRequest parsedRequest,
            boolean specialDay
    ) {
        String primaryZoneId = primary == null ? null : primary.zoneId();
        List<String> requiredCapabilities = requiredCapabilities(parsedRequest, specialDay);
        return new TripPlanningSpecification.DayStrategy(
                day,
                themeForDay(day, primary, specialDay),
                primaryZoneId,
                fallbackZoneIds(primaryZoneId, zones),
                allocation(primary),
                preferredPoiTypes(parsedRequest, specialDay),
                requiredCapabilities
        );
    }

    private ZoneContext primaryZoneForDay(
            int day,
            List<ZoneContext> zones,
            List<TripPlanningSpecification.SpecialEvent> specialEvents
    ) {
        if (zones == null || zones.isEmpty()) {
            return null;
        }
        if (hasSpecialEvent(specialEvents, day)) {
            return zones.stream()
                    .max(Comparator.comparingInt(this::birthdaySuitabilityScore))
                    .orElse(zones.getFirst());
        }
        return zones.get((day - 1) % zones.size());
    }

    private int birthdaySuitabilityScore(ZoneContext zone) {
        if (zone == null) {
            return 0;
        }
        int score = zone.mealSupport().lunchOptions() + zone.mealSupport().dinnerOptions();
        if (zone.themes().stream().anyMatch(theme -> "culture".equalsIgnoreCase(theme))) {
            score += 3;
        }
        if (zone.themes().stream().anyMatch(theme -> "local-dining".equalsIgnoreCase(theme))) {
            score += 3;
        }
        return score;
    }

    private List<TripPlanningSpecification.SpecialEvent> specialEvents(ParsedPlanningRequest parsedRequest) {
        if (parsedRequest == null || parsedRequest.specialDayHints() == null || parsedRequest.specialDayHints().isEmpty()) {
            return List.of();
        }
        return parsedRequest.specialDayHints().stream()
                .filter(hint -> hint != null && hint.day() > 0)
                .map(hint -> new TripPlanningSpecification.SpecialEvent(
                        hint.day(),
                        normalizeEventType(hint.type()),
                        requiredCapabilitiesForEvent(hint.type())
                ))
                .toList();
    }

    private boolean hasSpecialEvent(List<TripPlanningSpecification.SpecialEvent> specialEvents, int day) {
        return specialEvents != null && specialEvents.stream()
                .anyMatch(event -> event.day() == day);
    }

    private String normalizeEventType(String type) {
        if (type == null || type.isBlank()) {
            return "SPECIAL_EVENT";
        }
        String normalized = type.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return "BIRTHDAY".equals(normalized) ? "BIRTHDAY" : normalized;
    }

    private List<String> requiredCapabilitiesForEvent(String type) {
        return "BIRTHDAY".equals(normalizeEventType(type)) ? List.of("birthday-suitable") : List.of();
    }

    private List<String> requiredCapabilities(ParsedPlanningRequest parsedRequest, boolean specialDay) {
        List<String> capabilities = new ArrayList<>();
        if (parsedRequest != null && parsedRequest.familyFriendly()) {
            capabilities.add("family-friendly");
        }
        if (parsedRequest != null && parsedRequest.preferIndoorWhenRaining()) {
            capabilities.add("indoor-or-rainy-day");
        }
        if (specialDay) {
            capabilities.add("birthday-suitable");
        }
        return List.copyOf(capabilities);
    }

    private List<String> preferredPoiTypes(ParsedPlanningRequest parsedRequest, boolean specialDay) {
        List<String> preferred = new ArrayList<>();
        if (parsedRequest != null && parsedRequest.preferenceHints() != null) {
            preferred.addAll(parsedRequest.preferenceHints());
        }
        if (specialDay && !preferred.contains("local-dining")) {
            preferred.add("local-dining");
        }
        return List.copyOf(preferred);
    }

    private List<String> fallbackZoneIds(String primaryZoneId, List<ZoneContext> zones) {
        if (zones == null || zones.isEmpty()) {
            return List.of();
        }
        return zones.stream()
                .map(ZoneContext::zoneId)
                .filter(zoneId -> zoneId != null && !zoneId.isBlank())
                .filter(zoneId -> !zoneId.equals(primaryZoneId))
                .limit(2)
                .toList();
    }

    private String themeForDay(int day, ZoneContext primary, boolean specialDay) {
        if (specialDay) {
            return "Special occasion in " + zoneName(primary, day);
        }
        if (primary != null && primary.themes() != null && !primary.themes().isEmpty()) {
            return primary.themes().getFirst() + " in " + zoneName(primary, day);
        }
        return "Day " + day + " local highlights";
    }

    private String zoneName(ZoneContext zone, int day) {
        return zone == null || zone.name() == null || zone.name().isBlank() ? "day " + day : zone.name();
    }

    private String allocation(ZoneContext zone) {
        if (zone == null || zone.recommendedAllocation().isBlank()) {
            return "FULL_DAY";
        }
        return zone.recommendedAllocation();
    }
}
