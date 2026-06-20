package thesis.project.gu.planning.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import thesis.project.gu.planning.domain.PlanningContextVersion;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanDraftResponse(
        String city,
        String country,
        int days,
        String currency,
        CreatePlanReq.Party party,
        String pace,
        String title,
        String overview,
        List<DayPlan> daysPlan,
        String copyPolishStatus,
        String routeStatus,
        String planStatus,
        String planningMode,
        String catalogStatus,
        String copyStatus,
        String enhancementStatus,
        List<String> warnings,
        PlanningContextVersion contextVersion,
        String planVersion,
        String basePlanVersion
) {
    public PlanDraftResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        contextVersion = contextVersion == null ? PlanningContextVersion.unknown() : contextVersion;
        planVersion = planVersion == null || planVersion.isBlank()
                ? PlanningContextVersion.INITIAL_PLAN_VERSION
                : planVersion;
        basePlanVersion = basePlanVersion == null ? "" : basePlanVersion;
    }

    public PlanDraftResponse(
            String city,
            String country,
            int days,
            String currency,
            CreatePlanReq.Party party,
            String pace,
            String title,
            String overview,
            List<DayPlan> daysPlan,
            String copyPolishStatus
    ) {
        this(city, country, days, currency, party, pace, title, overview, daysPlan, copyPolishStatus, "UNKNOWN");
    }

    public PlanDraftResponse(
            String city,
            String country,
            int days,
            String currency,
            CreatePlanReq.Party party,
            String pace,
            String title,
            String overview,
            List<DayPlan> daysPlan,
            String copyPolishStatus,
            String routeStatus
    ) {
        this(
                city,
                country,
                days,
                currency,
                party,
                pace,
                title,
                overview,
                daysPlan,
                copyPolishStatus,
                routeStatus,
                "UNKNOWN",
                "UNKNOWN",
                "UNKNOWN",
                normalizeCopyStatus(copyPolishStatus),
                "UNKNOWN",
                List.of()
        );
    }

    public PlanDraftResponse(
            String city,
            String country,
            int days,
            String currency,
            CreatePlanReq.Party party,
            String pace,
            String title,
            String overview,
            List<DayPlan> daysPlan,
            String copyPolishStatus,
            String routeStatus,
            String planStatus,
            String planningMode,
            String catalogStatus,
            String copyStatus,
            String enhancementStatus,
            List<String> warnings
    ) {
        this(
                city,
                country,
                days,
                currency,
                party,
                pace,
                title,
                overview,
                daysPlan,
                copyPolishStatus,
                routeStatus,
                planStatus,
                planningMode,
                catalogStatus,
                copyStatus,
                enhancementStatus,
                warnings,
                PlanningContextVersion.unknown(),
                PlanningContextVersion.INITIAL_PLAN_VERSION,
                ""
        );
    }

    private static String normalizeCopyStatus(String copyPolishStatus) {
        if (copyPolishStatus == null || copyPolishStatus.isBlank()) {
            return "BASIC";
        }
        String normalized = copyPolishStatus.trim().toLowerCase(java.util.Locale.ROOT);
        if ("completed".equals(normalized)) {
            return "COMPLETED";
        }
        if ("deferred".equals(normalized) || "pending".equals(normalized)) {
            return "PENDING";
        }
        return "BASIC";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DayPlan(
            int dayIndex,
            Place hotel,
            List<Place> stops,
            String theme,
            String morningNote,
            String afternoonNote,
            String eveningNote,
            String note
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(
            String name,
            String addressLine,
            String suburb,
            String city,
            String state,
            String postcode,
            String country,
            String category,
            Integer stayMinutes,
            String timeSlot,
            String startTime,
            String endTime,
            String mealType,
            String preferredArea,
            String cuisine,
            String vibe,
            String budgetLevel,
            String reason,
            String tip,
            String websiteUri,
            String googleMapsUri,
            String businessStatus,
            String url,
            Double latitude,
            Double longitude
    ) {}
}
