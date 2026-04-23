package thesis.project.gu.model;

public record RouteChoice(
        ModeSummary walk,
        ModeSummary transit,
        ModeSummary car,
        ModeSummary recommended
) {}
