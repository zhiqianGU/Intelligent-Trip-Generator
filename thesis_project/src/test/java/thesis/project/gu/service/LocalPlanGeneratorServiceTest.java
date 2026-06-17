package thesis.project.gu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.local.LocalPoiCatalogService;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.localfast.LocalPlanGeneratorService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPlanGeneratorServiceTest {
    private final LocalPoiCatalogService catalogService = new LocalPoiCatalogService(new ObjectMapper());
    private final LocalPlanGeneratorService service = new LocalPlanGeneratorService(catalogService);

    @Test
    void generatesThreeDayBrisbanePlanWithMeals() {
        PlanDraftResponse draft = service.generate(req(3, List.of("culture"), "normal", 0));

        assertThat(draft.city()).isEqualTo("Brisbane");
        assertThat(draft.days()).isEqualTo(3);
        assertThat(draft.daysPlan()).hasSize(3);
        assertThat(draft.copyPolishStatus()).isEqualTo("local-fast");
        assertThat(draft.daysPlan()).allSatisfy(this::hasLunchAndDinner);
    }

    @Test
    void domainGeneratorPathReturnsPlanDraftWithoutResponseAdapter() {
        CreatePlanReq req = req(2, List.of("culture"), "normal", 0);
        TripPlanningSpecification specification = TripPlanningSpecification.fromRequest(req);
        PlaceCandidatePool candidatePool = catalogService.buildCandidatePool(specification);

        PlanDraft draft = service.generate(specification, candidatePool);

        assertThat(draft.city()).isEqualTo("Brisbane");
        assertThat(draft.days()).isEqualTo(2);
        assertThat(draft.daysPlan()).hasSize(2);
        assertThat(draft.daysPlan().getFirst()).isInstanceOf(PlanDraft.DayPlan.class);
        assertThat(draft.daysPlan().getFirst().stops().getFirst()).isInstanceOf(PlanDraft.Place.class);
        assertThat(draft.toResponse().daysPlan()).allSatisfy(this::hasLunchAndDinner);
    }

    @Test
    void generatesGenericCityPlanFromCanonicalLocalPoiFiles() {
        PlanDraftResponse draft = service.generate(new CreatePlanReq(
                "Testville",
                1,
                1000,
                new CreatePlanReq.Party(2, 0),
                List.of("culture"),
                "normal",
                "local-fast",
                null
        ));

        assertThat(draft.city()).isEqualTo("Testville");
        assertThat(draft.country()).isEqualTo("Testland");
        assertThat(draft.currency()).isEqualTo("TST");
        assertThat(draft.daysPlan()).hasSize(1);
        assertThat(draft.daysPlan().getFirst().stops()).allSatisfy(stop -> {
            assertThat(stop.city()).isEqualTo("Testville");
            assertThat(stop.state()).isEqualTo("TS");
            assertThat(stop.country()).isEqualTo("Testland");
        });
        hasLunchAndDinner(draft.daysPlan().getFirst());
    }

    @Test
    void generatesTenDayPlanWithoutDuplicateNonMealPois() {
        PlanDraftResponse draft = service.generate(req(10, List.of("nature", "culture"), "normal", 0));

        assertThat(draft.daysPlan()).hasSize(10);
        assertNoDuplicateNonMealPois(draft);
    }

    @Test
    void generatesTwentyDayPlanWithinCurrentBrisbaneDatasetCapacity() {
        long startedAt = System.currentTimeMillis();
        PlanDraftResponse draft = service.generate(req(20, List.of(), "normal", 0));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(draft.daysPlan()).hasSize(20);
        assertThat(totalNonMealStops(draft)).isBetween(50, 60);
        assertNoDuplicateNonMealPois(draft);
        assertThat(elapsedMs).isLessThan(1000);
    }

    @Test
    void familyPlanReducesDailyDensity() {
        PlanDraftResponse draft = service.generate(req(10, List.of("nature"), "normal", 2));

        assertThat(totalNonMealStops(draft)).isEqualTo(20);
        assertNoDuplicateNonMealPois(draft);
    }

    @Test
    void normalPlanKeepsLunchInsidePracticalWindow() {
        PlanDraftResponse draft = service.generate(req(5, List.of(), "normal", 0));

        assertThat(draft.daysPlan()).allSatisfy(day ->
                day.stops().stream()
                        .filter(stop -> "lunch".equalsIgnoreCase(stop.mealType()))
                        .findFirst()
                        .ifPresent(lunch -> assertThat(minutes(lunch.startTime())).isLessThanOrEqualTo(13 * 60 + 30))
        );
    }

    @Test
    void earlyNormalDaysKeepNonMealStopsInOneAreaWhenEnoughPoisExist() {
        PlanDraftResponse draft = service.generate(req(5, List.of(), "normal", 0));

        assertThat(draft.daysPlan()).allSatisfy(day -> {
            List<PlanDraftResponse.Place> nonMealStops = day.stops().stream()
                    .filter(stop -> !isMeal(stop))
                    .toList();
            assertThat(nonMealStops).isNotEmpty();
            String anchorArea = nonMealStops.getFirst().preferredArea();
            assertThat(nonMealStops)
                    .allSatisfy(stop -> assertThat(stop.preferredArea()).isEqualTo(anchorArea));
        });
    }

    @Test
    void areaRotationAvoidsConsecutiveCoreUrbanDaysWhenAlternativesExist() {
        PlanDraftResponse draft = service.generate(req(10, List.of(), "normal", 0));

        List<String> dominantAreas = draft.daysPlan().stream()
                .map(this::dominantNonMealArea)
                .toList();

        for (int i = 1; i < dominantAreas.size(); i++) {
            String previous = dominantAreas.get(i - 1);
            String current = dominantAreas.get(i);
            assertThat(current).isNotEqualTo(previous);
            assertThat(isCoreUrbanArea(previous) && isCoreUrbanArea(current))
                    .as("core urban areas should not be adjacent: " + previous + " -> " + current)
                    .isFalse();
        }
    }

    private CreatePlanReq req(int days, List<String> styles, String pace, int kids) {
        return new CreatePlanReq(
                "Brisbane",
                days,
                1200,
                new CreatePlanReq.Party(2, kids),
                styles,
                pace,
                "local-fast",
                null
        );
    }

    private void hasLunchAndDinner(PlanDraftResponse.DayPlan day) {
        assertThat(day.hotel()).isNotNull();
        assertThat(day.stops()).anySatisfy(stop -> assertThat(stop.mealType()).isEqualTo("lunch"));
        assertThat(day.stops()).anySatisfy(stop -> assertThat(stop.mealType()).isEqualTo("dinner"));
        assertThat(day.stops()).allSatisfy(stop -> {
            assertThat(stop.name()).isNotBlank();
            assertThat(stop.latitude()).isNotNull();
            assertThat(stop.longitude()).isNotNull();
            if (stop.startTime() != null) {
                assertThat(stop.endTime()).isNotNull();
            }
        });
    }

    private void assertNoDuplicateNonMealPois(PlanDraftResponse draft) {
        Set<String> seen = new HashSet<>();
        for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
            for (PlanDraftResponse.Place stop : day.stops()) {
                if (isMeal(stop)) {
                    continue;
                }
                String key = stop.name().toLowerCase() + "|" + stop.addressLine().toLowerCase();
                assertThat(seen.add(key)).as("duplicate non-meal stop " + stop.name()).isTrue();
            }
        }
    }

    private int totalNonMealStops(PlanDraftResponse draft) {
        int count = 0;
        for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
            for (PlanDraftResponse.Place stop : day.stops()) {
                if (!isMeal(stop)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean isMeal(PlanDraftResponse.Place stop) {
        return "lunch".equalsIgnoreCase(stop.mealType()) || "dinner".equalsIgnoreCase(stop.mealType());
    }

    private String dominantNonMealArea(PlanDraftResponse.DayPlan day) {
        return day.stops().stream()
                .filter(stop -> !isMeal(stop))
                .findFirst()
                .map(PlanDraftResponse.Place::preferredArea)
                .orElse("");
    }

    private boolean isCoreUrbanArea(String area) {
        return "Brisbane CBD".equals(area) || "South Bank".equals(area);
    }

    private int minutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
