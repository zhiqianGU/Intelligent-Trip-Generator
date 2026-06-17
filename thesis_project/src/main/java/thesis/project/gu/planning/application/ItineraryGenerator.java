package thesis.project.gu.planning.application;

import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripPlanningSpecification;

public interface ItineraryGenerator {
    PlanDraft generate(TripPlanningSpecification specification, PlaceCandidatePool candidatePool);

    default PlanDraft generate(
            TripPlanningSpecification specification,
            PlaceCandidatePool candidatePool,
            TripSkeleton skeleton
    ) {
        return generate(specification, candidatePool);
    }
}
