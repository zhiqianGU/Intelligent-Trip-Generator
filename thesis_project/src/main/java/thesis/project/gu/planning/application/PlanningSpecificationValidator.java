package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;
import thesis.project.gu.planning.domain.SpecificationValidationResult;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.domain.ZoneContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PlanningSpecificationValidator {
    private static final Set<String> ALLOWED_ALLOCATIONS = Set.of("FULL_DAY", "HALF_DAY", "FULL_OR_HALF_DAY");

    public SpecificationValidationResult validate(
            TripPlanningSpecification specification,
            List<ZoneContext> legalZones,
            ParsedPlanningRequest parsedRequest
    ) {
        if (specification == null) {
            return new SpecificationValidationResult(null, false, false, false, List.of(issue("missing-specification", "Specification is missing.")));
        }
        List<SpecificationValidationResult.Issue> issues = new ArrayList<>();
        List<ZoneContext> zones = legalZones == null ? List.of() : legalZones.stream()
                .filter(zone -> zone != null && !isBlank(zone.zoneId()))
                .toList();
        int days = Math.max(1, specification.days());
        List<TripPlanningSpecification.SpecialEvent> specialEvents = repairSpecialEvents(
                specification.specialEvents(),
                parsedRequest,
                days,
                issues
        );
        List<TripPlanningSpecification.DayStrategy> repairedStrategies = repairDayStrategies(
                specification.dayStrategies(),
                zones,
                specialEvents,
                days,
                parsedRequest,
                issues
        );
        TripPlanningSpecification repaired = new TripPlanningSpecification(
                specification.destination(),
                days,
                specification.budget(),
                specification.party(),
                specification.styles(),
                specification.pace(),
                specification.mainModel(),
                specification.departureDate(),
                specification.constraints() == null ? TripPlanningSpecification.Constraints.defaults() : specification.constraints(),
                specialEvents,
                repairedStrategies
        );
        boolean inputValid = issues.isEmpty();
        return new SpecificationValidationResult(repaired, outputValid(repaired, zones), inputValid, !inputValid, issues);
    }

    private boolean outputValid(TripPlanningSpecification specification, List<ZoneContext> legalZones) {
        if (specification == null || specification.dayStrategies() == null || specification.dayStrategies().size() != Math.max(1, specification.days())) {
            return false;
        }
        if (legalZones == null || legalZones.isEmpty()) {
            return false;
        }
        return specification.dayStrategies().stream()
                .allMatch(strategy -> strategy != null && isLegalZone(strategy.primaryZoneId(), legalZones));
    }

    private List<TripPlanningSpecification.SpecialEvent> repairSpecialEvents(
            List<TripPlanningSpecification.SpecialEvent> events,
            ParsedPlanningRequest parsedRequest,
            int days,
            List<SpecificationValidationResult.Issue> issues
    ) {
        List<TripPlanningSpecification.SpecialEvent> source = events == null || events.isEmpty()
                ? eventsFromParsedRequest(parsedRequest)
                : events;
        List<TripPlanningSpecification.SpecialEvent> repaired = new ArrayList<>();
        for (TripPlanningSpecification.SpecialEvent event : source) {
            if (event == null) {
                issues.add(issue("null-special-event", "Removed null special event."));
                continue;
            }
            if (event.day() < 1 || event.day() > days) {
                issues.add(issue("invalid-special-event-day", "Removed special event outside trip days."));
                continue;
            }
            String type = normalizeEventType(event.type());
            List<String> required = ensureEventCapabilities(type, event.requiredCapabilities());
            if (!type.equals(event.type()) || required.size() != safeList(event.requiredCapabilities()).size()) {
                issues.add(issue("repaired-special-event", "Normalized special event type or capabilities."));
            }
            repaired.add(new TripPlanningSpecification.SpecialEvent(event.day(), type, required));
        }
        return List.copyOf(repaired);
    }

    private List<TripPlanningSpecification.DayStrategy> repairDayStrategies(
            List<TripPlanningSpecification.DayStrategy> strategies,
            List<ZoneContext> legalZones,
            List<TripPlanningSpecification.SpecialEvent> specialEvents,
            int days,
            ParsedPlanningRequest parsedRequest,
            List<SpecificationValidationResult.Issue> issues
    ) {
        List<TripPlanningSpecification.DayStrategy> repaired = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            TripPlanningSpecification.DayStrategy original = strategyForDay(strategies, day);
            if (original == null) {
                issues.add(issue("missing-day-strategy", "Inserted missing day strategy for day " + day + "."));
            }
            repaired.add(repairDayStrategy(original, day, legalZones, specialEvents, parsedRequest, issues));
        }
        if (strategies != null && strategies.stream().filter(strategy -> strategy != null && strategy.day() > days).findAny().isPresent()) {
            issues.add(issue("extra-day-strategy", "Removed day strategies outside requested trip days."));
        }
        return reduceConsecutiveZoneRepeats(repaired, legalZones, specialEvents, issues);
    }

    private TripPlanningSpecification.DayStrategy repairDayStrategy(
            TripPlanningSpecification.DayStrategy original,
            int day,
            List<ZoneContext> legalZones,
            List<TripPlanningSpecification.SpecialEvent> specialEvents,
            ParsedPlanningRequest parsedRequest,
            List<SpecificationValidationResult.Issue> issues
    ) {
        ZoneContext primary = resolvePrimaryZone(original, day, legalZones);
        if (original != null && !isLegalZone(original.primaryZoneId(), legalZones)) {
            issues.add(issue("illegal-primary-zone", "Replaced illegal primary zone for day " + day + "."));
        }
        String primaryZoneId = primary == null ? null : primary.zoneId();
        List<String> fallbackZoneIds = repairFallbackZoneIds(original, primaryZoneId, legalZones, issues, day);
        String allocation = repairAllocation(original == null ? null : original.allocation(), primary);
        if (original != null && !allocation.equals(original.allocation())) {
            issues.add(issue("invalid-allocation", "Repaired allocation for day " + day + "."));
        }
        boolean specialDay = hasSpecialEvent(specialEvents, day);
        List<String> requiredCapabilities = mergeRequiredCapabilities(
                original == null ? List.of() : original.requiredCapabilities(),
                specialDay,
                parsedRequest
        );
        return new TripPlanningSpecification.DayStrategy(
                day,
                theme(original, primary, day, specialDay),
                primaryZoneId,
                fallbackZoneIds,
                allocation,
                safeList(original == null ? List.of() : original.preferredPoiTypes()),
                requiredCapabilities
        );
    }

    private ZoneContext resolvePrimaryZone(
            TripPlanningSpecification.DayStrategy strategy,
            int day,
            List<ZoneContext> legalZones
    ) {
        if (legalZones.isEmpty()) {
            return null;
        }
        if (strategy != null && isLegalZone(strategy.primaryZoneId(), legalZones)) {
            return zoneById(strategy.primaryZoneId(), legalZones);
        }
        if (strategy != null) {
            for (String fallbackZoneId : safeList(strategy.fallbackZoneIds())) {
                if (isLegalZone(fallbackZoneId, legalZones)) {
                    return zoneById(fallbackZoneId, legalZones);
                }
            }
        }
        return legalZones.get((day - 1) % legalZones.size());
    }

    private List<String> repairFallbackZoneIds(
            TripPlanningSpecification.DayStrategy strategy,
            String primaryZoneId,
            List<ZoneContext> legalZones,
            List<SpecificationValidationResult.Issue> issues,
            int day
    ) {
        List<String> repaired = new ArrayList<>();
        if (strategy != null) {
            for (String fallbackZoneId : safeList(strategy.fallbackZoneIds())) {
                if (isLegalZone(fallbackZoneId, legalZones) && !fallbackZoneId.equals(primaryZoneId) && !repaired.contains(fallbackZoneId)) {
                    repaired.add(fallbackZoneId);
                }
            }
        }
        int beforeFill = repaired.size();
        for (ZoneContext zone : legalZones) {
            if (repaired.size() >= 2) {
                break;
            }
            if (!zone.zoneId().equals(primaryZoneId) && !repaired.contains(zone.zoneId())) {
                repaired.add(zone.zoneId());
            }
        }
        if (strategy != null && safeList(strategy.fallbackZoneIds()).size() != beforeFill) {
            issues.add(issue("invalid-fallback-zone", "Removed illegal fallback zone for day " + day + "."));
        }
        return List.copyOf(repaired);
    }

    private List<TripPlanningSpecification.DayStrategy> reduceConsecutiveZoneRepeats(
            List<TripPlanningSpecification.DayStrategy> strategies,
            List<ZoneContext> legalZones,
            List<TripPlanningSpecification.SpecialEvent> specialEvents,
            List<SpecificationValidationResult.Issue> issues
    ) {
        if (legalZones.size() < 2 || strategies.size() < 2) {
            return List.copyOf(strategies);
        }
        List<TripPlanningSpecification.DayStrategy> repaired = new ArrayList<>(strategies);
        for (int i = 1; i < repaired.size(); i++) {
            TripPlanningSpecification.DayStrategy previous = repaired.get(i - 1);
            TripPlanningSpecification.DayStrategy current = repaired.get(i);
            if (hasSpecialEvent(specialEvents, current.day())) {
                continue;
            }
            if (previous.primaryZoneId() != null && previous.primaryZoneId().equals(current.primaryZoneId())) {
                ZoneContext replacement = firstDifferentZone(current.primaryZoneId(), legalZones);
                if (replacement != null) {
                    repaired.set(i, replacePrimaryZone(current, replacement, legalZones));
                    issues.add(issue("repaired-consecutive-zone", "Changed repeated primary zone for day " + current.day() + "."));
                }
            }
        }
        return List.copyOf(repaired);
    }

    private TripPlanningSpecification.DayStrategy replacePrimaryZone(
            TripPlanningSpecification.DayStrategy strategy,
            ZoneContext replacement,
            List<ZoneContext> legalZones
    ) {
        return new TripPlanningSpecification.DayStrategy(
                strategy.day(),
                strategy.theme(),
                replacement.zoneId(),
                legalZones.stream()
                        .map(ZoneContext::zoneId)
                        .filter(zoneId -> !zoneId.equals(replacement.zoneId()))
                        .limit(2)
                        .toList(),
                repairAllocation(strategy.allocation(), replacement),
                strategy.preferredPoiTypes(),
                strategy.requiredCapabilities()
        );
    }

    private List<TripPlanningSpecification.SpecialEvent> eventsFromParsedRequest(ParsedPlanningRequest parsedRequest) {
        if (parsedRequest == null || parsedRequest.specialDayHints() == null) {
            return List.of();
        }
        return parsedRequest.specialDayHints().stream()
                .filter(hint -> hint != null)
                .map(hint -> new TripPlanningSpecification.SpecialEvent(
                        hint.day(),
                        normalizeEventType(hint.type()),
                        ensureEventCapabilities(hint.type(), List.of())
                ))
                .toList();
    }

    private TripPlanningSpecification.DayStrategy strategyForDay(List<TripPlanningSpecification.DayStrategy> strategies, int day) {
        if (strategies == null) {
            return null;
        }
        return strategies.stream()
                .filter(strategy -> strategy != null && strategy.day() == day)
                .findFirst()
                .orElse(null);
    }

    private String repairAllocation(String allocation, ZoneContext zone) {
        String normalized = allocation == null ? "" : allocation.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (ALLOWED_ALLOCATIONS.contains(normalized)) {
            return normalized;
        }
        if (zone != null && !isBlank(zone.recommendedAllocation())) {
            String zoneAllocation = zone.recommendedAllocation().trim().toUpperCase(Locale.ROOT);
            return ALLOWED_ALLOCATIONS.contains(zoneAllocation) ? zoneAllocation : "FULL_DAY";
        }
        return "FULL_DAY";
    }

    private List<String> mergeRequiredCapabilities(
            List<String> existing,
            boolean specialDay,
            ParsedPlanningRequest parsedRequest
    ) {
        Set<String> capabilities = new LinkedHashSet<>(safeList(existing));
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

    private List<String> ensureEventCapabilities(String type, List<String> existing) {
        Set<String> capabilities = new LinkedHashSet<>(safeList(existing));
        if ("BIRTHDAY".equals(normalizeEventType(type))) {
            capabilities.add("birthday-suitable");
        }
        return List.copyOf(capabilities);
    }

    private String normalizeEventType(String type) {
        if (isBlank(type)) {
            return "SPECIAL_EVENT";
        }
        return type.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private boolean hasSpecialEvent(List<TripPlanningSpecification.SpecialEvent> specialEvents, int day) {
        return specialEvents != null && specialEvents.stream().anyMatch(event -> event.day() == day);
    }

    private String theme(
            TripPlanningSpecification.DayStrategy original,
            ZoneContext primary,
            int day,
            boolean specialDay
    ) {
        if (original != null && !isBlank(original.theme())) {
            return original.theme();
        }
        if (specialDay) {
            return "Special occasion in " + zoneName(primary, day);
        }
        return "Day " + day + " in " + zoneName(primary, day);
    }

    private ZoneContext firstDifferentZone(String zoneId, List<ZoneContext> legalZones) {
        return legalZones.stream()
                .filter(zone -> !zone.zoneId().equals(zoneId))
                .findFirst()
                .orElse(null);
    }

    private ZoneContext zoneById(String zoneId, List<ZoneContext> legalZones) {
        return legalZones.stream()
                .filter(zone -> zone.zoneId().equals(zoneId))
                .findFirst()
                .orElse(null);
    }

    private boolean isLegalZone(String zoneId, List<ZoneContext> legalZones) {
        return !isBlank(zoneId) && zoneById(zoneId, legalZones) != null;
    }

    private String zoneName(ZoneContext zone, int day) {
        return zone == null || isBlank(zone.name()) ? "day " + day : zone.name();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private SpecificationValidationResult.Issue issue(String code, String message) {
        return new SpecificationValidationResult.Issue(code, message);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
