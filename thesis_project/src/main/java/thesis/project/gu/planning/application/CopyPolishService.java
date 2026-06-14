package thesis.project.gu.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.planning.ai.TripAiService;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.DayPlan;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.Place;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

@Service
public class CopyPolishService {
    private static final Logger log = LoggerFactory.getLogger(CopyPolishService.class);

    private final TripAiService aiService;

    public CopyPolishService(TripAiService aiService) {
        this.aiService = aiService;
    }

    public PlanDraftResponse applyCopyPolishPatch(
            PlanDraftResponse verifiedDraft,
            UnaryOperator<PlanDraftResponse> finalizer
    ) {
        if (verifiedDraft == null) {
            return null;
        }
        try {
            TripAiService.CopyPolishResult result = aiService.polishPlanCopy(verifiedDraft);
            if (result.completed()) {
                return withCopyPolishStatus(mergeAllowedCopyFields(verifiedDraft, result.draft()), "completed", finalizer);
            }
            return withCopyPolishStatus(verifiedDraft, "fallback-" + result.status(), finalizer);
        } catch (Exception e) {
            log.debug("Async copy polish fallback to verified draft", e);
            return withCopyPolishStatus(verifiedDraft, "error", finalizer);
        }
    }

    public PlanDraftResponse withCopyPolishStatus(
            PlanDraftResponse draft,
            String status,
            UnaryOperator<PlanDraftResponse> finalizer
    ) {
        if (draft == null) {
            return null;
        }
        PlanDraftResponse safeDraft = finalizer == null ? draft : finalizer.apply(draft);
        return new PlanDraftResponse(
                safeDraft.city(),
                safeDraft.country(),
                safeDraft.days(),
                safeDraft.currency(),
                safeDraft.party(),
                safeDraft.pace(),
                safeDraft.title(),
                safeDraft.overview(),
                safeDraft.daysPlan(),
                status
        );
    }

    PlanDraftResponse mergeAllowedCopyFields(PlanDraftResponse base, PlanDraftResponse polished) {
        if (base == null || polished == null) {
            return base;
        }
        List<DayPlan> baseDays = base.daysPlan() == null ? List.of() : base.daysPlan();
        List<DayPlan> polishedDays = polished.daysPlan() == null ? List.of() : polished.daysPlan();
        List<DayPlan> mergedDays = new ArrayList<>();
        for (int i = 0; i < baseDays.size(); i++) {
            DayPlan baseDay = baseDays.get(i);
            DayPlan polishedDay = i < polishedDays.size() ? polishedDays.get(i) : null;
            mergedDays.add(mergeDayCopy(baseDay, polishedDay));
        }
        return new PlanDraftResponse(
                base.city(),
                base.country(),
                base.days(),
                base.currency(),
                base.party(),
                base.pace(),
                base.title(),
                selectCopy(polished.overview(), base.overview()),
                mergedDays,
                base.copyPolishStatus()
        );
    }

    private DayPlan mergeDayCopy(DayPlan base, DayPlan polished) {
        if (base == null) {
            return null;
        }
        List<Place> baseStops = base.stops() == null ? List.of() : base.stops();
        List<Place> polishedStops = polished == null || polished.stops() == null ? List.of() : polished.stops();
        List<Place> mergedStops = new ArrayList<>();
        for (int i = 0; i < baseStops.size(); i++) {
            Place polishedStop = i < polishedStops.size() ? polishedStops.get(i) : null;
            mergedStops.add(mergePlaceCopy(baseStops.get(i), polishedStop));
        }
        return new DayPlan(
                base.dayIndex(),
                mergePlaceCopy(base.hotel(), polished == null ? null : polished.hotel()),
                mergedStops,
                base.theme(),
                base.morningNote(),
                base.afternoonNote(),
                base.eveningNote(),
                base.note()
        );
    }

    private Place mergePlaceCopy(Place base, Place polished) {
        if (base == null) {
            return null;
        }
        String reason = selectCopy(polished == null ? null : polished.reason(), base.reason());
        String tip = selectCopy(polished == null ? null : polished.tip(), base.tip());
        if (isThemeParkLikeStop(base)) {
            reason = sanitizeThemeParkCopy(reason);
            tip = sanitizeThemeParkCopy(tip);
        }
        return new Place(
                base.name(),
                base.addressLine(),
                base.suburb(),
                base.city(),
                base.state(),
                base.postcode(),
                base.country(),
                base.category(),
                base.stayMinutes(),
                base.timeSlot(),
                base.startTime(),
                base.endTime(),
                base.mealType(),
                base.preferredArea(),
                base.cuisine(),
                base.vibe(),
                base.budgetLevel(),
                reason,
                tip,
                base.websiteUri(),
                base.googleMapsUri(),
                base.businessStatus(),
                base.url(),
                base.latitude(),
                base.longitude()
        );
    }

    private String selectCopy(String candidate, String fallback) {
        String selected = candidate == null || candidate.isBlank() ? fallback : candidate.trim();
        return sanitizeNarrativeCopy(selected);
    }

    private String sanitizeNarrativeCopy(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replaceAll("(?i)\\bwalkable\\b", "compact")
                .replaceAll("(?i)\\btransit-friendly\\b", "manageable")
                .replaceAll("(?i)\\btransit friendly\\b", "manageable")
                .replaceAll("(?i)\\btour access\\b", "scheduled access")
                .replaceAll("(?i)\\btours\\b", "scheduled visits")
                .replaceAll("(?i)\\btour\\b", "scheduled visit")
                .trim();
    }

    private String sanitizeThemeParkCopy(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replaceAll("(?i)\\bwalkable\\b", "compact")
                .replaceAll("(?i)\\btransit-friendly\\b", "manageable")
                .replaceAll("(?i)\\btransit friendly\\b", "manageable")
                .replaceAll("(?i)\\btour access\\b", "scheduled access")
                .replaceAll("(?i)\\btours\\b", "scheduled visits")
                .replaceAll("(?i)\\btour\\b", "scheduled visit")
                .replaceAll("(?i)\\bpriority access\\b", "optional add-ons")
                .replaceAll("(?i)\\btimed entry\\b", "entry requirements")
                .replaceAll("(?i)\\bguaranteed\\b", "planned")
                .trim();
    }

    private boolean isThemeParkLikeStop(Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String text = (nullToEmpty(stop.name()) + " " + nullToEmpty(stop.addressLine()) + " " + category).toLowerCase(Locale.ROOT);
        return "theme_park".equals(category)
                || "amusement".equals(category)
                || "amusement_park".equals(category)
                || text.contains("theme park")
                || text.contains("amusement park")
                || text.contains("water park");
    }

    private String normalizeSlot(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
