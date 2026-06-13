package thesis.project.gu.planning.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
        String copyPolishStatus
) {
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
