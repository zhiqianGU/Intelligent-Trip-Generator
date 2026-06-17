package thesis.project.gu.catalog.application;

import thesis.project.gu.catalog.domain.PlanningZoneRetrievalResult;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;

import java.util.List;

public interface PlanningZoneRetrievalService {
    PlanningZoneRetrievalResult retrieve(
            PlanningZoneRetrievalQuery query,
            List<PlanningZoneSummary> availableZones
    );
}
