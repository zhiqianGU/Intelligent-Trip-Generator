package thesis.project.gu.planning.domain;

import java.util.List;

public record PlanningAgentInput(
        TripPlanningSpecification specification,
        ParsedPlanningRequest parsedRequest,
        List<ZoneContext> zoneContexts,
        PlanningContextVersion contextVersion
) {
    public PlanningAgentInput {
        zoneContexts = zoneContexts == null ? List.of() : List.copyOf(zoneContexts);
        contextVersion = contextVersion == null ? PlanningContextVersion.localFirst() : contextVersion;
    }

    public PlanningAgentInput(
            TripPlanningSpecification specification,
            ParsedPlanningRequest parsedRequest,
            List<ZoneContext> zoneContexts
    ) {
        this(specification, parsedRequest, zoneContexts, PlanningContextVersion.localFirst());
    }
}
