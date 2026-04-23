package thesis.project.gu.model;

public record RouteSegmentSuggestion(
        int segmentIndex,
        String from,
        String to,
        String recommendedMode,
        Integer durationMinutes,
        Integer distanceMeters,
        ModeSummary walk,
        ModeSummary transit,
        ModeSummary car,
        String hint,
        boolean hidden
) {}
