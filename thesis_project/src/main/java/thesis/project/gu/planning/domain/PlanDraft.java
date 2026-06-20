package thesis.project.gu.planning.domain;

import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;

import java.util.List;

public record PlanDraft(
        String city,
        String country,
        int days,
        String currency,
        Party party,
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
    public PlanDraft {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        contextVersion = contextVersion == null ? PlanningContextVersion.unknown() : contextVersion;
        planVersion = planVersion == null || planVersion.isBlank()
                ? PlanningContextVersion.INITIAL_PLAN_VERSION
                : planVersion;
        basePlanVersion = basePlanVersion == null ? "" : basePlanVersion;
    }

    public PlanDraft(
            String city,
            String country,
            int days,
            String currency,
            Party party,
            String pace,
            String title,
            String overview,
            List<DayPlan> daysPlan,
            String copyPolishStatus
    ) {
        this(city, country, days, currency, party, pace, title, overview, daysPlan, copyPolishStatus, "UNKNOWN");
    }

    public PlanDraft(
            String city,
            String country,
            int days,
            String currency,
            Party party,
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

    public PlanDraft(
            String city,
            String country,
            int days,
            String currency,
            Party party,
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

    public static PlanDraft fromResponse(PlanDraftResponse response) {
        if (response == null) {
            return null;
        }
        return new PlanDraft(
                response.city(),
                response.country(),
                response.days(),
                response.currency(),
                fromResponseParty(response.party()),
                response.pace(),
                response.title(),
                response.overview(),
                response.daysPlan() == null
                        ? List.of()
                        : response.daysPlan().stream().map(DayPlan::fromResponse).toList(),
                response.copyPolishStatus(),
                response.routeStatus(),
                response.planStatus(),
                response.planningMode(),
                response.catalogStatus(),
                response.copyStatus(),
                response.enhancementStatus(),
                response.warnings(),
                response.contextVersion(),
                response.planVersion(),
                response.basePlanVersion()
        );
    }

    public PlanDraftResponse toResponse() {
        return new PlanDraftResponse(
                city,
                country,
                days,
                currency,
                party == null ? null : new CreatePlanReq.Party(party.adults(), party.kids()),
                pace,
                title,
                overview,
                daysPlan == null ? List.of() : daysPlan.stream().map(DayPlan::toResponse).toList(),
                copyPolishStatus,
                routeStatus,
                planStatus,
                planningMode,
                catalogStatus,
                copyStatus,
                enhancementStatus,
                warnings,
                contextVersion,
                planVersion,
                basePlanVersion
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

    private static Party fromResponseParty(CreatePlanReq.Party party) {
        return party == null ? null : new Party(party.adults(), party.kids());
    }

    public record Party(Integer adults, Integer kids) {
    }

    public record DayPlan(
            int dayIndex,
            Place hotel,
            List<Place> stops,
            String theme,
            String morningNote,
            String afternoonNote,
            String eveningNote,
            String note
    ) {
        public static DayPlan fromResponse(PlanDraftResponse.DayPlan day) {
            if (day == null) {
                return null;
            }
            return new DayPlan(
                    day.dayIndex(),
                    Place.fromResponse(day.hotel()),
                    day.stops() == null ? List.of() : day.stops().stream().map(Place::fromResponse).toList(),
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            );
        }

        public PlanDraftResponse.DayPlan toResponse() {
            return new PlanDraftResponse.DayPlan(
                    dayIndex,
                    hotel == null ? null : hotel.toResponse(),
                    stops == null ? List.of() : stops.stream().map(Place::toResponse).toList(),
                    theme,
                    morningNote,
                    afternoonNote,
                    eveningNote,
                    note
            );
        }
    }

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
    ) {
        public static Place fromResponse(PlanDraftResponse.Place place) {
            if (place == null) {
                return null;
            }
            return new Place(
                    place.name(),
                    place.addressLine(),
                    place.suburb(),
                    place.city(),
                    place.state(),
                    place.postcode(),
                    place.country(),
                    place.category(),
                    place.stayMinutes(),
                    place.timeSlot(),
                    place.startTime(),
                    place.endTime(),
                    place.mealType(),
                    place.preferredArea(),
                    place.cuisine(),
                    place.vibe(),
                    place.budgetLevel(),
                    place.reason(),
                    place.tip(),
                    place.websiteUri(),
                    place.googleMapsUri(),
                    place.businessStatus(),
                    place.url(),
                    place.latitude(),
                    place.longitude()
            );
        }

        public PlanDraftResponse.Place toResponse() {
            return new PlanDraftResponse.Place(
                    name,
                    addressLine,
                    suburb,
                    city,
                    state,
                    postcode,
                    country,
                    category,
                    stayMinutes,
                    timeSlot,
                    startTime,
                    endTime,
                    mealType,
                    preferredArea,
                    cuisine,
                    vibe,
                    budgetLevel,
                    reason,
                    tip,
                    websiteUri,
                    googleMapsUri,
                    businessStatus,
                    url,
                    latitude,
                    longitude
            );
        }
    }
}
