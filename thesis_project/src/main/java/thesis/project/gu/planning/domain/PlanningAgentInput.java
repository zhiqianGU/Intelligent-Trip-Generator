package thesis.project.gu.planning.domain;

import java.util.List;

public record PlanningAgentInput(
        TripPlanningSpecification specification,
        ParsedPlanningRequest parsedRequest,
        List<ZoneContext> zoneContexts
) {
    public PlanningAgentInput {
        zoneContexts = zoneContexts == null ? List.of() : List.copyOf(zoneContexts);
    }
}
