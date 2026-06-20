package thesis.project.gu.planning.domain;

import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.domain.Destination;
import thesis.project.gu.catalog.local.LocalPoiCatalog;
import thesis.project.gu.catalog.local.LocalPoiItem;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningDomainBoundaryTest {

    @Test
    void tripPlanningSpecificationRoundTripsCreatePlanRequest() {
        CreatePlanReq req = new CreatePlanReq(
                "Brisbane",
                5,
                1500,
                new CreatePlanReq.Party(2, 1),
                List.of("nature", "culture"),
                "normal",
                "local-fast",
                "2026-07-01"
        );

        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req);
        CreatePlanReq roundTrip = spec.toRequest();

        assertThat(spec.destination().city()).isEqualTo("Brisbane");
        assertThat(spec.days()).isEqualTo(5);
        assertThat(spec.party().kids()).isEqualTo(1);
        assertThat(spec.styles()).containsExactly("nature", "culture");
        assertThat(roundTrip).isEqualTo(req);
    }

    @Test
    void tripPlanningSpecificationCarriesResolvedDestinationWithoutChangingRequestShape() {
        CreatePlanReq req = new CreatePlanReq(
                "Brisbane",
                3,
                1200,
                new CreatePlanReq.Party(2, 0),
                List.of("culture"),
                "normal",
                "local-fast",
                null
        );
        Destination resolvedDestination = new Destination(
                "AU-QLD-BRISBANE",
                "Brisbane",
                "Queensland",
                "Australia",
                "Australia/Brisbane",
                true
        );

        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req)
                .withResolvedDestination(resolvedDestination);

        assertThat(spec.destination().destinationId()).isEqualTo("AU-QLD-BRISBANE");
        assertThat(spec.destination().timezone()).isEqualTo("Australia/Brisbane");
        assertThat(spec.destination().resolved()).isTrue();
        assertThat(spec.toRequest()).isEqualTo(req);
    }

    @Test
    void placeCandidatePoolRoundTripsLocalCatalog() {
        LocalPoiItem hotel = item("Base Hotel", "hotel");
        LocalPoiItem attraction = item("City Gallery", "attraction");
        LocalPoiItem restaurant = item("River Dining", "restaurant");
        LocalPoiCatalog catalog = new LocalPoiCatalog(
                "Brisbane",
                "Australia",
                "QLD",
                "AUD",
                List.of(hotel),
                List.of(attraction),
                List.of(restaurant)
        );

        PlaceCandidatePool pool = PlaceCandidatePool.fromLocalCatalog(catalog);
        LocalPoiCatalog roundTrip = pool.toLocalCatalog();

        assertThat(pool.totalItemCount()).isEqualTo(3);
        assertThat(pool.hotels()).extracting(PlaceCandidate::name).containsExactly(hotel.name());
        assertThat(pool.hotels()).extracting(PlaceCandidate::type).containsExactly(PlaceCandidateType.HOTEL);
        assertThat(pool.attractions()).extracting(PlaceCandidate::name).containsExactly(attraction.name());
        assertThat(pool.attractions()).extracting(PlaceCandidate::type).containsExactly(PlaceCandidateType.ATTRACTION);
        assertThat(pool.restaurants()).extracting(PlaceCandidate::name).containsExactly(restaurant.name());
        assertThat(pool.restaurants()).extracting(PlaceCandidate::type).containsExactly(PlaceCandidateType.RESTAURANT);
        assertThat(roundTrip).isEqualTo(catalog);
    }

    @Test
    void planDraftRoundTripsApiResponseWithoutSharingWrapperState() {
        PlanDraftResponse response = new PlanDraftResponse(
                "Brisbane",
                "Australia",
                1,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "normal",
                "Title",
                "Overview",
                List.of(new PlanDraftResponse.DayPlan(
                        1,
                        responsePlace("Hotel", "hotel", "night", null),
                        List.of(
                                responsePlace("Gallery", "gallery", "morning", null),
                                responsePlace("Lunch", "restaurant", "lunch", "lunch")
                        ),
                        "CBD day",
                        "Morning",
                        "Afternoon",
                        "Evening",
                        "Note"
                )),
                "local-fast",
                "ESTIMATED",
                "READY",
                "ZONE_GUIDED_LOCAL_FIRST",
                "SUFFICIENT",
                "BASIC",
                "PENDING",
                List.of("coverage-soft-gap:day1:ACTIVITY:missing=1"),
                new PlanningContextVersion(
                        "catalog-v2",
                        "zone-v3",
                        "semantic-v1",
                        "embedding-v0",
                        "prompt-v4",
                        "spec-v5"
                ),
                "plan-v7",
                "plan-v6"
        );

        PlanDraft draft = PlanDraft.fromResponse(response);
        PlanDraftResponse roundTrip = draft.toResponse();

        assertThat(draft.city()).isEqualTo("Brisbane");
        assertThat(draft.party()).isEqualTo(new PlanDraft.Party(2, 0));
        assertThat(draft.routeStatus()).isEqualTo("ESTIMATED");
        assertThat(draft.planStatus()).isEqualTo("READY");
        assertThat(draft.planningMode()).isEqualTo("ZONE_GUIDED_LOCAL_FIRST");
        assertThat(draft.catalogStatus()).isEqualTo("SUFFICIENT");
        assertThat(draft.copyStatus()).isEqualTo("BASIC");
        assertThat(draft.enhancementStatus()).isEqualTo("PENDING");
        assertThat(draft.warnings()).containsExactly("coverage-soft-gap:day1:ACTIVITY:missing=1");
        assertThat(draft.contextVersion().catalogVersion()).isEqualTo("catalog-v2");
        assertThat(draft.contextVersion().zoneSnapshotVersion()).isEqualTo("zone-v3");
        assertThat(draft.planVersion()).isEqualTo("plan-v7");
        assertThat(draft.basePlanVersion()).isEqualTo("plan-v6");
        assertThat(draft.daysPlan()).hasSize(1);
        assertThat(draft.daysPlan().getFirst().stops().getFirst())
                .isInstanceOf(PlanDraft.Place.class);
        assertThat(roundTrip).isEqualTo(response);
    }

    @Test
    void legacyPlanDraftResponseConstructorDoesNotPretendToUseLocalFirstContext() {
        PlanDraftResponse response = new PlanDraftResponse(
                "Sydney",
                "Australia",
                1,
                "AUD",
                new CreatePlanReq.Party(2, 0),
                "normal",
                "Title",
                "Overview",
                List.of(),
                null
        );

        assertThat(response.contextVersion().catalogVersion()).isEqualTo("unknown");
        assertThat(response.contextVersion().zoneSnapshotVersion()).isEqualTo("unknown");
        assertThat(response.contextVersion().embeddingVersion()).isEqualTo("unknown");
    }

    private LocalPoiItem item(String name, String type) {
        return new LocalPoiItem(
                name,
                type,
                type,
                "Brisbane",
                "CBD",
                name + " address",
                -27.47,
                153.02,
                60,
                List.of("culture"),
                List.of("morning"),
                90,
                true,
                "medium",
                "restaurant".equals(type) ? List.of("lunch", "dinner") : List.of(),
                null,
                "local",
                "curated"
        );
    }

    private PlanDraftResponse.Place responsePlace(String name, String category, String timeSlot, String mealType) {
        return new PlanDraftResponse.Place(
                name,
                name + " address",
                "CBD",
                "Brisbane",
                "QLD",
                null,
                "Australia",
                category,
                60,
                timeSlot,
                "09:00",
                "10:00",
                mealType,
                "CBD",
                null,
                null,
                "medium",
                "reason",
                "tip",
                null,
                null,
                "OPERATIONAL",
                null,
                -27.47,
                153.02
        );
    }
}
