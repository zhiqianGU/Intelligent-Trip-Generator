package thesis.project.gu.planning.domain;

import java.util.List;

public record ParsedPlanningRequest(
        String destinationCandidate,
        int daysCandidate,
        int travellers,
        String pace,
        Integer budget,
        List<String> preferenceHints,
        List<SpecialDayHint> specialDayHints,
        boolean lateStartPreferred,
        boolean familyFriendly,
        boolean preferIndoorWhenRaining,
        String rawText
) {
    public record SpecialDayHint(
            int day,
            String type,
            String rawExpression
    ) {
    }
}
