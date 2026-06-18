package thesis.project.gu.catalog.application;

import thesis.project.gu.catalog.domain.CoverageResult;
import thesis.project.gu.catalog.domain.AvailableZoneSummary;
import thesis.project.gu.catalog.domain.PlanningZoneSnapshot;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripPlanningSpecification;

import java.util.List;

public interface DestinationCatalog {
    List<PlanningZoneSummary> findAvailableZones(TripPlanningSpecification specification);

    default List<PlanningZoneSnapshot> findZoneSnapshots(TripPlanningSpecification specification) {
        return List.of();
    }

    default List<AvailableZoneSummary> findAvailableZoneSummaries(
            TripPlanningSpecification specification,
            List<PlanningZoneSummary> candidateZones
    ) {
        return List.of();
    }

    TripSkeleton buildTripSkeleton(TripPlanningSpecification specification);

    default TripSkeleton buildTripSkeleton(
            TripPlanningSpecification specification,
            List<PlanningZoneSummary> orderedZones
    ) {
        return buildTripSkeleton(specification);
    }

    CoverageResult checkCoverage(
            TripPlanningSpecification specification,
            TripSkeleton skeleton,
            PlaceCandidatePool candidatePool
    );

    PlaceCandidatePool buildCandidatePool(TripPlanningSpecification specification);
}
