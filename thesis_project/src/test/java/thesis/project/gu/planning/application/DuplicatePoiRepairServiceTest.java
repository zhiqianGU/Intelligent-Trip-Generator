package thesis.project.gu.planning.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.scheduling.DaySkeletonService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DuplicatePoiRepairServiceTest {
    @Mock
    private GooglePlacesClient googlePlacesClient;
    @Mock
    private PlaceHeuristicService placeHeuristicService;

    @Test
    void repairSameDayDuplicatePois_replacesDuplicateWhenDropIsUnsafe() {
        when(googlePlacesClient.isEnabled()).thenReturn(false);
        when(placeHeuristicService.corePoiName(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(placeHeuristicService.normalizeSearchText(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(placeHeuristicService.commonSignificantTokenCount(anyString(), anyString())).thenReturn(0);

        DuplicatePoiRepairService service = new DuplicatePoiRepairService(
                googlePlacesClient,
                placeHeuristicService,
                new DaySkeletonService()
        );

        PlanDraftResponse draft = draft(
                day(1, List.of(
                        place("City Botanic Gardens", "morning", "09:00", "10:00", null),
                        place("City Botanic Gardens", "afternoon", "13:00", "14:00", null),
                        place("South Bank Parklands", "sunset", "16:00", "17:00", null),
                        place("Lunch Spot", "lunch", "12:10", "13:10", "lunch"),
                        place("Dinner Spot", "dinner", "18:00", "19:00", "dinner")
                ))
        );

        PlanDraftResponse repaired = service.repairSameDayDuplicatePois(draft);

        assertThat(repaired.daysPlan()).hasSize(1);
        assertThat(repaired.daysPlan().getFirst().stops()).hasSize(5);
        assertThat(repaired.daysPlan().getFirst().stops().getFirst().name()).isEqualTo("City Botanic Gardens");
        assertThat(repaired.daysPlan().getFirst().stops().get(1).name()).isNotEqualTo("City Botanic Gardens");
        assertThat(repaired.daysPlan().getFirst().stops().get(1).name()).containsIgnoringCase("stroll");
    }

    @Test
    void repairCrossDayDuplicatePois_replacesLaterDuplicateWhenDropIsUnsafe() {
        when(googlePlacesClient.isEnabled()).thenReturn(false);
        when(placeHeuristicService.corePoiName(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(placeHeuristicService.normalizeSearchText(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(placeHeuristicService.commonSignificantTokenCount(anyString(), anyString())).thenReturn(0);

        DuplicatePoiRepairService service = new DuplicatePoiRepairService(
                googlePlacesClient,
                placeHeuristicService,
                new DaySkeletonService()
        );

        PlanDraftResponse draft = draft(
                day(1, List.of(
                        place("City Botanic Gardens", "morning", "09:00", "10:00", null),
                        place("Queens Botanical Garden", "afternoon", "13:00", "14:00", null),
                        place("Lunch Spot", "lunch", "12:10", "13:10", "lunch"),
                        place("Dinner Spot", "dinner", "18:00", "19:00", "dinner")
                )),
                day(2, List.of(
                        place("City Botanic Gardens", "morning", "09:00", "10:00", null),
                        place("South Bank Parklands", "afternoon", "13:00", "14:00", null),
                        place("Roma Street Parkland", "sunset", "16:00", "17:00", null),
                        place("Lunch Spot 2", "lunch", "12:10", "13:10", "lunch"),
                        place("Dinner Spot 2", "dinner", "18:00", "19:00", "dinner")
                ))
        );

        PlanDraftResponse repaired = service.repairCrossDayDuplicatePois(draft);

        assertThat(repaired.daysPlan()).hasSize(2);
        assertThat(repaired.daysPlan().get(0).stops()).hasSize(4);
        assertThat(repaired.daysPlan().get(1).stops()).hasSize(5);
        assertThat(repaired.daysPlan().get(1).stops().getFirst().name()).isNotEqualTo("City Botanic Gardens");
        assertThat(repaired.daysPlan().get(1).stops().getFirst().name()).containsIgnoringCase("stroll");
        assertThat(repaired.daysPlan().get(0).stops().getFirst().name()).isEqualTo("City Botanic Gardens");
    }

    private PlanDraftResponse draft(PlanDraftResponse.DayPlan... days) {
        return new PlanDraftResponse(
                "Brisbane",
                "Australia",
                days.length,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "normal",
                "Title",
                "Overview",
                List.of(days),
                null
        );
    }

    private PlanDraftResponse.DayPlan day(int dayIndex, List<PlanDraftResponse.Place> stops) {
        return new PlanDraftResponse.DayPlan(
                dayIndex,
                place("Hotel", "morning", "08:00", "09:00", null),
                stops,
                "Theme",
                "Morning note",
                "Afternoon note",
                "Evening note",
                "Note"
        );
    }

    private PlanDraftResponse.Place place(String name, String timeSlot, String startTime, String endTime, String mealType) {
        return new PlanDraftResponse.Place(
                name,
                name + " address",
                "South Brisbane",
                "Brisbane",
                "QLD",
                "4000",
                "Australia",
                mealType == null ? "attraction" : "restaurant",
                60,
                timeSlot,
                startTime,
                endTime,
                mealType,
                "South Brisbane",
                null,
                null,
                null,
                "Reason",
                "Tip",
                null,
                null,
                null,
                null,
                -27.47,
                153.02
        );
    }
}
