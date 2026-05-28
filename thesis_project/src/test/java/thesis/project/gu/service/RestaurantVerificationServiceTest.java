package thesis.project.gu.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import thesis.project.gu.client.GooglePlacesClient;
import thesis.project.gu.req.CreatePlanReq;
import thesis.project.gu.response.PlanDraftResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantVerificationServiceTest {

    @Mock
    private GooglePlacesClient googlePlacesClient;

    @Test
    void verifyAndNormalizeRunsDayMealSearchInBoundedParallelAndKeepsStopOrder() {
        RestaurantVerificationService service = new RestaurantVerificationService(googlePlacesClient);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(googlePlacesClient.isEnabled()).thenReturn(true);
        when(googlePlacesClient.searchText(anyString(), anyString())).thenAnswer(invocation -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.updateAndGet(previous -> Math.max(previous, current));
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return List.<GooglePlacesClient.PlaceCandidate>of();
        });

        PlanDraftResponse draft = draftWithMultipleMeals();
        List<String> inputOrder = draft.daysPlan().getFirst().stops().stream()
                .map(PlanDraftResponse.Place::name)
                .toList();

        PlanDraftResponse normalized = service.verifyAndNormalize(draft).draft();

        List<String> outputOrder = normalized.daysPlan().getFirst().stops().stream()
                .map(PlanDraftResponse.Place::name)
                .toList();
        assertThat(outputOrder).containsExactlyElementsOf(inputOrder);
        assertThat(maxInFlight.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void verifyAndNormalizeSelectiveOnlyQueriesTargetedFoodStops() {
        RestaurantVerificationService service = new RestaurantVerificationService(googlePlacesClient);

        when(googlePlacesClient.isEnabled()).thenReturn(true);
        when(googlePlacesClient.searchText(anyString(), anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0, String.class);
            if (query != null && query.toLowerCase().contains("lunch")) {
                return List.<GooglePlacesClient.PlaceCandidate>of(candidate("Target Lunch Venue"));
            }
            return List.of();
        });

        PlanDraftResponse draft = new PlanDraftResponse(
                "Brisbane",
                "Australia",
                1,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "normal",
                "Brisbane Day",
                "Overview",
                List.of(new PlanDraftResponse.DayPlan(
                        1,
                        place("Hotel", "hotel", "night", "20:00", "21:00", null),
                        List.of(
                                place("City Museum", "museum", "morning", "09:00", "10:00", null),
                                place("Lunch Candidate", "restaurant", "lunch", "12:00", "13:00", "lunch"),
                                place("River Walk", "attraction", "afternoon", "14:00", "15:00", null),
                                place("Dinner Candidate", "restaurant", "dinner", "18:00", "19:00", "dinner")
                        ),
                        "Theme",
                        "Morning",
                        "Afternoon",
                        "Evening",
                        "Note"
                )),
                null
        );

        PlanDraftResponse normalized = service.verifyAndNormalizeSelective(draft, Map.of(1, Set.of(1))).draft();

        assertThat(normalized.daysPlan().getFirst().stops().get(1).name()).isEqualTo("Target Lunch Venue");
        assertThat(normalized.daysPlan().getFirst().stops().get(3).name()).isEqualTo("Dinner Candidate");
        verify(googlePlacesClient, never()).searchText(org.mockito.ArgumentMatchers.contains("dinner"), anyString());
    }

    private PlanDraftResponse draftWithMultipleMeals() {
        List<PlanDraftResponse.Place> stops = List.of(
                place("City Museum", "museum", "morning", "09:00", "10:00", null),
                place("Lunch Candidate A", "restaurant", "lunch", "12:00", "13:00", "lunch"),
                place("Lunch Candidate B", "restaurant", "lunch", "12:30", "13:30", "lunch"),
                place("River Walk", "attraction", "afternoon", "14:00", "15:00", null),
                place("Dinner Candidate A", "restaurant", "dinner", "18:00", "19:00", "dinner"),
                place("Dinner Candidate B", "restaurant", "dinner", "19:10", "20:10", "dinner")
        );
        return new PlanDraftResponse(
                "Brisbane",
                "Australia",
                1,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "normal",
                "Brisbane Day",
                "Overview",
                List.of(new PlanDraftResponse.DayPlan(
                        1,
                        place("Hotel", "hotel", "night", "20:00", "21:00", null),
                        stops,
                        "Theme",
                        "Morning",
                        "Afternoon",
                        "Evening",
                        "Note"
                )),
                null
        );
    }

    private PlanDraftResponse.Place place(
            String name,
            String category,
            String timeSlot,
            String start,
            String end,
            String mealType
    ) {
        return new PlanDraftResponse.Place(
                name,
                name + " address",
                "CBD",
                "Brisbane",
                "QLD",
                "4000",
                "Australia",
                category,
                stayMinutes(start, end),
                timeSlot,
                start,
                end,
                mealType,
                "CBD",
                category.equals("restaurant") ? "Modern Australian" : null,
                category.equals("restaurant") ? "Casual" : null,
                category.equals("restaurant") ? "midrange" : null,
                "Reason",
                "Tip",
                null,
                null,
                "OPERATIONAL",
                null,
                null,
                null
        );
    }

    private Integer stayMinutes(String start, String end) {
        String[] startParts = start.split(":");
        String[] endParts = end.split(":");
        int startMinutes = Integer.parseInt(startParts[0]) * 60 + Integer.parseInt(startParts[1]);
        int endMinutes = Integer.parseInt(endParts[0]) * 60 + Integer.parseInt(endParts[1]);
        return endMinutes - startMinutes;
    }

    private GooglePlacesClient.PlaceCandidate candidate(String name) {
        return new GooglePlacesClient.PlaceCandidate(
                name,
                name + ", CBD QLD 4000",
                "OPERATIONAL",
                List.of("restaurant"),
                "https://example.com/" + name.replace(' ', '-'),
                "https://maps.google.com/?q=" + name.replace(' ', '+'),
                false,
                -27.470,
                153.020
        );
    }
}
