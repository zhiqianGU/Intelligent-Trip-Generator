package thesis.project.gu.planning.quality;

import thesis.project.gu.planning.domain.PlanDraft;

public interface PlanQualityService {
    LocalPlanQualityReport diagnose(PlanDraft draft);
}
