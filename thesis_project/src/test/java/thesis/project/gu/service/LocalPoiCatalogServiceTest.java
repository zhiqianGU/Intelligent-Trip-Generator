package thesis.project.gu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.domain.AvailableZoneSummary;
import thesis.project.gu.catalog.domain.CoverageResult;
import thesis.project.gu.catalog.domain.PlanningZoneSnapshot;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.catalog.local.LocalPoiCatalog;
import thesis.project.gu.catalog.local.LocalPoiItem;
import thesis.project.gu.catalog.local.LocalPoiCatalogService;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripSlot;
import thesis.project.gu.planning.domain.TripPlanningSpecification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPoiCatalogServiceTest {
    private final LocalPoiCatalogService service = new LocalPoiCatalogService(new ObjectMapper());

    @Test
    void loadsBrisbaneLocalCatalogFromResourceFiles() {
        LocalPoiCatalog catalog = service.catalogForCity("Brisbane");

        assertThat(catalog.city()).isEqualTo("Brisbane");
        assertThat(catalog.country()).isEqualTo("Australia");
        assertThat(catalog.state()).isEqualTo("QLD");
        assertThat(catalog.currency()).isEqualTo("AUD");
        assertThat(catalog.hotels()).hasSize(8);
        assertThat(catalog.attractions()).hasSize(90);
        assertThat(catalog.restaurants()).hasSize(72);
        assertThat(catalog.totalItemCount()).isEqualTo(170);
    }

    @Test
    void loadsGenericCityCatalogFromCanonicalResourceFiles() {
        LocalPoiCatalog catalog = service.catalogForCity("Testville");

        assertThat(catalog.city()).isEqualTo("Testville");
        assertThat(catalog.country()).isEqualTo("Testland");
        assertThat(catalog.state()).isEqualTo("TS");
        assertThat(catalog.currency()).isEqualTo("TST");
        assertThat(catalog.hotels()).hasSize(1);
        assertThat(catalog.attractions()).hasSize(2);
        assertThat(catalog.restaurants()).hasSize(2);
        assertThat(catalog.totalItemCount()).isEqualTo(5);
        assertThat(catalog.hotels().getFirst().type()).isEqualTo("hotel");
        assertThat(catalog.attractions().getFirst().type()).isEqualTo("attraction");
        assertThat(catalog.restaurants().getFirst().type()).isEqualTo("restaurant");
    }

    @Test
    void brisbaneCatalogItemsHaveRequiredSchedulingFields() {
        LocalPoiCatalog catalog = service.catalogForCity("brisbane");

        assertThat(catalog.hotels()).allSatisfy(this::hasCoreFields);
        assertThat(catalog.attractions()).allSatisfy(item -> {
            hasCoreFields(item);
            assertThat(item.timeSlots()).isNotEmpty();
            assertThat(item.priority()).isBetween(1, 100);
        });
        assertThat(catalog.restaurants()).allSatisfy(item -> {
            hasCoreFields(item);
            assertThat(item.mealTypes()).isNotEmpty();
            assertThat(item.cuisine()).isNotBlank();
        });
    }

    @Test
    void unsupportedCityReturnsEmptyCatalog() {
        LocalPoiCatalog catalog = service.catalogForCity("Sydney");

        assertThat(catalog.city()).isEqualTo("Sydney");
        assertThat(catalog.totalItemCount()).isZero();
    }

    @Test
    void buildsStaticZoneSummariesFromLocalPoiAreas() {
        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req("Brisbane", 3, "normal"));

        List<PlanningZoneSummary> zones = service.findAvailableZones(spec);

        assertThat(zones).isNotEmpty();
        assertThat(zones).anySatisfy(zone -> {
            assertThat(zone.zoneId()).isEqualTo("brisbane-south-bank");
            assertThat(zone.capabilities().attractionCount()).isGreaterThan(0);
            assertThat(zone.capabilities().categoryCounts()).isNotEmpty();
        });
    }

    @Test
    void buildsStaticZoneSnapshotsFromLocalPoiAreas() {
        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req("Brisbane", 3, "normal"));

        List<PlanningZoneSnapshot> snapshots = service.findZoneSnapshots(spec);

        assertThat(snapshots).isNotEmpty();
        assertThat(snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.zoneId()).isEqualTo("brisbane-south-bank");
            assertThat(snapshot.zoneType()).isEqualTo("URBAN_DISTRICT");
            assertThat(snapshot.totalAttractionCount()).isGreaterThan(0);
            assertThat(snapshot.totalRestaurantCount()).isGreaterThan(0);
            assertThat(snapshot.anchorPoiIds()).isNotEmpty();
            assertThat(snapshot.semanticProfile()).contains("south-bank");
            assertThat(snapshot.snapshotVersion()).isEqualTo("local-poi-v1");
            assertThat(snapshot.generatedAt()).isNotBlank();
        });
    }

    @Test
    void zoneSnapshotBuildReusesCandidatePoolWithinSingleCall() {
        CountingLocalPoiCatalogService countingService = new CountingLocalPoiCatalogService(new ObjectMapper());
        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req("Brisbane", 3, "normal"));

        List<PlanningZoneSnapshot> snapshots = countingService.findZoneSnapshots(spec);

        assertThat(snapshots).isNotEmpty();
        assertThat(countingService.buildCandidatePoolCalls).isLessThanOrEqualTo(2);
    }

    @Test
    void buildsRequestScopedAvailableZoneSummaries() {
        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req("Brisbane", 3, "relaxed", 240, 1));
        List<PlanningZoneSummary> zones = service.findAvailableZones(spec);
        PlanningZoneSummary southBank = zones.stream()
                .filter(zone -> "brisbane-south-bank".equals(zone.zoneId()))
                .findFirst()
                .orElseThrow();

        List<AvailableZoneSummary> summaries = service.findAvailableZoneSummaries(spec, List.of(southBank));

        assertThat(summaries).hasSize(1);
        AvailableZoneSummary summary = summaries.getFirst();
        assertThat(summary.zoneId()).isEqualTo("brisbane-south-bank");
        assertThat(summary.availableAttractionCount()).isGreaterThanOrEqualTo(0);
        assertThat(summary.availableFamilyCount()).isGreaterThanOrEqualTo(0);
        assertThat(summary.availableLunchCount()).isGreaterThanOrEqualTo(0);
        assertThat(summary.availableDinnerCount()).isGreaterThanOrEqualTo(0);
        assertThat(summary.requestScopedCapacity()).isGreaterThanOrEqualTo(0);
        assertThat(summary.freshnessStatus()).isEqualTo("LOCAL_CURATED");
    }

    @Test
    void diningPreferenceDoesNotFilterOutAttractionAvailability() {
        TripPlanningSpecification noStyleSpec = TripPlanningSpecification.fromRequest(req("Brisbane", 3, "normal", 1200, 0, List.of()));
        TripPlanningSpecification diningSpec = TripPlanningSpecification.fromRequest(req("Brisbane", 3, "normal", 1200, 0, List.of("food")));
        PlanningZoneSummary southBank = service.findAvailableZones(noStyleSpec).stream()
                .filter(zone -> "brisbane-south-bank".equals(zone.zoneId()))
                .findFirst()
                .orElseThrow();

        AvailableZoneSummary noStyleSummary = service.findAvailableZoneSummaries(noStyleSpec, List.of(southBank)).getFirst();
        AvailableZoneSummary diningSummary = service.findAvailableZoneSummaries(diningSpec, List.of(southBank)).getFirst();

        assertThat(noStyleSummary.availableAttractionCount()).isGreaterThan(0);
        assertThat(diningSummary.availableAttractionCount()).isEqualTo(noStyleSummary.availableAttractionCount());
        assertThat(diningSummary.requestScopedCapacity()).isEqualTo(noStyleSummary.requestScopedCapacity());
    }

    @Test
    void buildsTripSkeletonBeforeCoverageCheck() {
        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req("Brisbane", 2, "relaxed"));

        TripSkeleton skeleton = service.buildTripSkeleton(spec);

        assertThat(skeleton.days()).hasSize(2);
        assertThat(skeleton.days().getFirst().startTime()).isEqualTo("10:00");
        assertThat(skeleton.days().getFirst().slots())
                .extracting(TripSlot::slotType)
                .contains(TripSlot.SlotType.ACTIVITY, TripSlot.SlotType.LUNCH, TripSlot.SlotType.DINNER);
    }

    @Test
    void buildsTripSkeletonUsingRetrievedZoneOrder() {
        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req("Brisbane", 2, "normal"));
        List<PlanningZoneSummary> zones = service.findAvailableZones(spec);
        PlanningZoneSummary southBank = zones.stream()
                .filter(zone -> "brisbane-south-bank".equals(zone.zoneId()))
                .findFirst()
                .orElseThrow();

        TripSkeleton skeleton = service.buildTripSkeleton(spec, List.of(southBank));

        assertThat(skeleton.days()).hasSize(2);
        assertThat(skeleton.days())
                .allSatisfy(day -> assertThat(day.zoneId()).isEqualTo("brisbane-south-bank"));
    }

    @Test
    void buildsTripSkeletonFromDayStrategyAllocationAndFallbackZones() {
        TripPlanningSpecification spec = strategySpec(
                1,
                List.of(),
                List.of(new TripPlanningSpecification.DayStrategy(
                        1,
                        "South Bank short culture day",
                        "brisbane-south-bank",
                        List.of("brisbane-cbd"),
                        "HALF_DAY",
                        List.of("culture"),
                        List.of("family-friendly")
                ))
        );

        TripSkeleton skeleton = service.buildTripSkeleton(spec, service.findAvailableZones(spec));

        TripSkeleton.DaySkeleton day = skeleton.days().getFirst();
        assertThat(day.zoneId()).isEqualTo("brisbane-south-bank");
        assertThat(day.fallbackZoneIds()).containsExactly("brisbane-cbd");
        assertThat(day.slots())
                .filteredOn(slot -> slot.slotType() == TripSlot.SlotType.ACTIVITY)
                .hasSize(2)
                .allSatisfy(slot -> {
                    assertThat(slot.requiredCapabilities()).contains("family-friendly", "poi-type:culture");
                    assertThat(slot.preferredDurationMinutes()).isEqualTo(75);
                    assertThat(slot.preferredTimeWindow()).isNotNull();
                });
    }

    @Test
    void diningPreferenceDoesNotBecomeActivityRequiredCapability() {
        TripPlanningSpecification spec = strategySpec(
                1,
                List.of(),
                List.of(new TripPlanningSpecification.DayStrategy(
                        1,
                        "Dining and culture day",
                        "brisbane-south-bank",
                        List.of("brisbane-cbd"),
                        "FULL_DAY",
                        List.of("culture", "local-dining"),
                        List.of()
                ))
        );

        TripSkeleton skeleton = service.buildTripSkeleton(spec, service.findAvailableZones(spec));

        assertThat(skeleton.days().getFirst().slots())
                .filteredOn(slot -> slot.slotType() == TripSlot.SlotType.ACTIVITY)
                .allSatisfy(slot -> {
                    assertThat(slot.requiredCapabilities()).contains("poi-type:culture");
                    assertThat(slot.requiredCapabilities()).doesNotContain("poi-type:local-dining");
                });
    }

    @Test
    void buildsSpecialEventSlotFromDayStrategyAndSpecialEvent() {
        TripPlanningSpecification spec = strategySpec(
                1,
                List.of(new TripPlanningSpecification.SpecialEvent(1, "BIRTHDAY", List.of("birthday-suitable"))),
                List.of(new TripPlanningSpecification.DayStrategy(
                        1,
                        "Birthday in South Bank",
                        "brisbane-south-bank",
                        List.of("brisbane-cbd"),
                        "FULL_DAY",
                        List.of("culture"),
                        List.of("family-friendly")
                ))
        );

        TripSkeleton skeleton = service.buildTripSkeleton(spec, service.findAvailableZones(spec));

        assertThat(skeleton.days().getFirst().slots())
                .anySatisfy(slot -> {
                    assertThat(slot.slotType()).isEqualTo(TripSlot.SlotType.SPECIAL_EVENT);
                    assertThat(slot.zoneId()).isEqualTo("brisbane-south-bank");
                    assertThat(slot.requiredCapabilities()).contains("birthday-suitable", "family-friendly");
                    assertThat(slot.preferredTimeWindow()).isEqualTo(new TripSlot.TimeWindow("17:00", "20:30"));
                });
        assertThat(skeleton.days().getFirst().slots())
                .filteredOn(slot -> slot.slotType() == TripSlot.SlotType.DINNER)
                .singleElement()
                .satisfies(slot -> assertThat(slot.requiredCapabilities()).contains("birthday-suitable"));
    }

    @Test
    void coverageCheckDistinguishesHardGapsFromReducedFallbackMargin() {
        TripPlanningSpecification spec = TripPlanningSpecification.fromRequest(req("Testville", 1, "normal"));
        TripSkeleton skeleton = service.buildTripSkeleton(spec);
        PlaceCandidatePool pool = service.buildCandidatePool(spec);

        CoverageResult coverage = service.checkCoverage(spec, skeleton, pool);

        assertThat(coverage.generatable()).isFalse();
        assertThat(coverage.gaps()).isNotEmpty();
        assertThat(coverage.gaps()).anySatisfy(gap -> {
            assertThat(gap.requiredUsageCount()).isGreaterThan(gap.availableCandidateCount());
            assertThat(gap.missingCount()).isGreaterThan(0);
        });
    }

    @Test
    void coverageCheckUsesSlotCapabilitiesFromRequestScopedInventory() {
        TripPlanningSpecification spec = new TripPlanningSpecification(
                new TripPlanningSpecification.Destination("Testville"),
                1,
                1200,
                new TripPlanningSpecification.Party(2, 0),
                List.of("culture"),
                "normal",
                "local-fast",
                null,
                TripPlanningSpecification.Constraints.defaults(),
                List.of(),
                List.of(new TripPlanningSpecification.DayStrategy(
                        1,
                        "Culture day",
                        "testville-central",
                        List.of(),
                        "FULL_DAY",
                        List.of("culture"),
                        List.of()
                ))
        );
        TripSkeleton skeleton = service.buildTripSkeleton(spec);
        PlaceCandidatePool pool = service.buildCandidatePool(spec);
        List<AvailableZoneSummary> summaries = service.findAvailableZoneSummaries(spec, service.findAvailableZones(spec));

        CoverageResult coverage = service.checkCoverage(spec, skeleton, pool, summaries);

        assertThat(coverage.generatable()).isFalse();
        assertThat(coverage.gaps()).anySatisfy(gap -> {
            assertThat(gap.slotType()).isEqualTo("ACTIVITY");
            assertThat(gap.requiredCapabilities()).contains("poi-type:culture");
            assertThat(gap.requiredUsageCount()).isEqualTo(3);
            assertThat(gap.availableCandidateCount()).isEqualTo(1);
        });
    }

    private void hasCoreFields(LocalPoiItem item) {
        assertThat(item.name()).isNotBlank();
        assertThat(item.category()).isNotBlank();
        assertThat(item.city()).isEqualToIgnoringCase("Brisbane");
        assertThat(item.area()).isNotBlank();
        assertThat(item.addressLine()).isNotBlank();
        assertThat(item.latitude()).isBetween(-28.5, -26.5);
        assertThat(item.longitude()).isBetween(152.0, 154.5);
        assertThat(item.budgetLevel()).isNotBlank();
        assertThat(item.familyFriendly()).isNotNull();
    }

    private CreatePlanReq req(String city, int days, String pace) {
        return req(city, days, pace, 1200, 0);
    }

    private CreatePlanReq req(String city, int days, String pace, int budget, int kids) {
        return req(city, days, pace, budget, kids, List.of("culture"));
    }

    private CreatePlanReq req(String city, int days, String pace, int budget, int kids, List<String> styles) {
        return new CreatePlanReq(
                city,
                days,
                budget,
                new CreatePlanReq.Party(2, kids),
                styles,
                pace,
                "local-fast",
                null
        );
    }

    private TripPlanningSpecification strategySpec(
            int days,
            List<TripPlanningSpecification.SpecialEvent> specialEvents,
            List<TripPlanningSpecification.DayStrategy> dayStrategies
    ) {
        TripPlanningSpecification base = TripPlanningSpecification.fromRequest(req("Brisbane", days, "normal"));
        return new TripPlanningSpecification(
                base.destination(),
                base.days(),
                base.budget(),
                base.party(),
                base.styles(),
                base.pace(),
                base.mainModel(),
                base.departureDate(),
                base.constraints(),
                specialEvents,
                dayStrategies
        );
    }

    private static class CountingLocalPoiCatalogService extends LocalPoiCatalogService {
        private int buildCandidatePoolCalls;

        private CountingLocalPoiCatalogService(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public PlaceCandidatePool buildCandidatePool(TripPlanningSpecification specification) {
            buildCandidatePoolCalls++;
            return super.buildCandidatePool(specification);
        }
    }
}
