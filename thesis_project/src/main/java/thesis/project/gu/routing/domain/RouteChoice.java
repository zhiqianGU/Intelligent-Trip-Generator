package thesis.project.gu.routing.domain;

public record RouteChoice(
        ModeSummary walk,
        ModeSummary transit,
        ModeSummary car,
        ModeSummary recommended
) {}
