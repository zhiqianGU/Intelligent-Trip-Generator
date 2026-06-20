package thesis.project.gu.planning.application;

import org.springframework.stereotype.Component;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.DayPlan;

import java.util.List;

@Component
public class PlanResponseAssembler {

    public PlanDraftResponse withDays(PlanDraftResponse draft, List<DayPlan> days) {
        if (draft == null) {
            return null;
        }
        return rebuild(
                draft,
                draft.pace(),
                draft.title(),
                draft.overview(),
                days,
                draft.copyPolishStatus()
        );
    }

    public PlanDraftResponse withPace(PlanDraftResponse draft, String pace) {
        if (draft == null) {
            return null;
        }
        return rebuild(
                draft,
                pace,
                draft.title(),
                draft.overview(),
                draft.daysPlan(),
                draft.copyPolishStatus()
        );
    }

    public PlanDraftResponse withCopyPolishStatus(PlanDraftResponse draft, String status) {
        if (draft == null) {
            return null;
        }
        return rebuild(draft, draft.pace(), draft.title(), draft.overview(), draft.daysPlan(), status);
    }

    public PlanDraftResponse rebuild(
            PlanDraftResponse draft,
            String pace,
            String title,
            String overview,
            List<DayPlan> days,
            String copyPolishStatus
    ) {
        if (draft == null) {
            return null;
        }
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                pace,
                title,
                overview,
                days,
                copyPolishStatus,
                draft.routeStatus(),
                draft.planStatus(),
                draft.planningMode(),
                draft.catalogStatus(),
                copyStatusFor(copyPolishStatus, draft.copyStatus()),
                draft.enhancementStatus(),
                draft.warnings(),
                draft.contextVersion(),
                draft.planVersion(),
                draft.basePlanVersion()
        );
    }

    private String copyStatusFor(String copyPolishStatus, String fallback) {
        if (copyPolishStatus == null || copyPolishStatus.isBlank()) {
            return fallback == null || fallback.isBlank() ? "BASIC" : fallback;
        }
        String normalized = copyPolishStatus.trim().toLowerCase(java.util.Locale.ROOT);
        if ("completed".equals(normalized)) {
            return "COMPLETED";
        }
        if ("deferred".equals(normalized) || "pending".equals(normalized)) {
            return "PENDING";
        }
        return fallback == null || fallback.isBlank() ? "BASIC" : fallback;
    }

    public CreatePlanReq withPace(CreatePlanReq req, String pace) {
        if (req == null) {
            return null;
        }
        return new CreatePlanReq(
                req.city(),
                req.days(),
                req.budget(),
                req.party(),
                req.style(),
                pace,
                req.mainModel(),
                req.departureDate()
        );
    }
}
