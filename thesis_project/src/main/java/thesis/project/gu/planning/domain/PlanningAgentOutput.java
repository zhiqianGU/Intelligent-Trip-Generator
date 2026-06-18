package thesis.project.gu.planning.domain;

import java.util.List;

public record PlanningAgentOutput(
        TripPlanningSpecification.Constraints normalizedConstraints,
        List<TripPlanningSpecification.SpecialEvent> specialEvents,
        List<TripPlanningSpecification.DayStrategy> dayStrategies,
        String plannerType,
        boolean fallbackUsed
) {
    public PlanningAgentOutput {
        specialEvents = specialEvents == null ? List.of() : List.copyOf(specialEvents);
        dayStrategies = dayStrategies == null ? List.of() : List.copyOf(dayStrategies);
        plannerType = plannerType == null ? "" : plannerType;
    }
}
