package thesis.project.gu.catalog.local;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.application.DestinationCatalog;
import thesis.project.gu.catalog.application.CoverageInventoryBuilder;
import thesis.project.gu.catalog.domain.AvailableZoneSummary;
import thesis.project.gu.catalog.domain.CatalogInventory;
import thesis.project.gu.catalog.domain.CoverageGap;
import thesis.project.gu.catalog.domain.CoverageInventory;
import thesis.project.gu.catalog.domain.CoverageResult;
import thesis.project.gu.catalog.domain.PlanningZoneSnapshot;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.catalog.domain.ZoneCapabilitySummary;
import thesis.project.gu.planning.domain.PlaceCandidate;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripSlot;
import thesis.project.gu.planning.domain.TripPlanningSpecification;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class LocalPoiCatalogService implements DestinationCatalog {
    private static final String LOCAL_POI_ROOT = "local-poi/";
    private static final String LOCAL_SNAPSHOT_VERSION = "local-poi-v1";
    private static final String LOCAL_FRESHNESS_STATUS = "LOCAL_CURATED";

    private final ObjectMapper objectMapper;
    private final CoverageInventoryBuilder coverageInventoryBuilder;
    private final Map<String, LocalPoiCatalog> catalogCache = new ConcurrentHashMap<>();

    public LocalPoiCatalogService(ObjectMapper objectMapper) {
        this(objectMapper, new CoverageInventoryBuilder());
    }

    @Autowired
    public LocalPoiCatalogService(ObjectMapper objectMapper, CoverageInventoryBuilder coverageInventoryBuilder) {
        this.objectMapper = objectMapper;
        this.coverageInventoryBuilder = coverageInventoryBuilder == null
                ? new CoverageInventoryBuilder()
                : coverageInventoryBuilder;
    }

    public LocalPoiCatalog catalogForCity(String city) {
        String normalizedCity = normalizeCity(city);
        if (normalizedCity.isBlank()) {
            return emptyCatalog("");
        }
        return catalogCache.computeIfAbsent(normalizedCity, this::loadCatalog);
    }

    @Override
    public PlaceCandidatePool buildCandidatePool(TripPlanningSpecification specification) {
        String city = specification == null || specification.destination() == null
                ? null
                : specification.destination().city();
        return PlaceCandidatePool.fromLocalCatalog(catalogForCity(city));
    }

    @Override
    public List<PlanningZoneSummary> findAvailableZones(TripPlanningSpecification specification) {
        PlaceCandidatePool pool = buildCandidatePool(specification);
        Map<String, List<PlaceCandidate>> byArea = allCandidates(pool).stream()
                .filter(item -> !isBlank(item.area()))
                .collect(Collectors.groupingBy(PlaceCandidate::area));
        return byArea.entrySet().stream()
                .map(entry -> toZoneSummary(pool.city(), entry.getKey(), entry.getValue()))
                .filter(zone -> zone.capabilities().attractionCount() > 0)
                .sorted(Comparator
                        .comparingInt((PlanningZoneSummary zone) -> zone.capabilities().normalDayCapacity()).reversed()
                        .thenComparing(PlanningZoneSummary::name))
                .toList();
    }

    @Override
    public List<PlanningZoneSnapshot> findZoneSnapshots(TripPlanningSpecification specification) {
        PlaceCandidatePool pool = buildCandidatePool(specification);
        return findAvailableZones(specification).stream()
                .map(zone -> toZoneSnapshot(pool, zone))
                .toList();
    }

    @Override
    public List<AvailableZoneSummary> findAvailableZoneSummaries(
            TripPlanningSpecification specification,
            List<PlanningZoneSummary> candidateZones
    ) {
        PlaceCandidatePool pool = buildCandidatePool(specification);
        List<PlanningZoneSummary> zones = candidateZones == null || candidateZones.isEmpty()
                ? findAvailableZones(specification)
                : candidateZones;
        return zones.stream()
                .map(zone -> toAvailableZoneSummary(specification, pool, zone))
                .toList();
    }

    @Override
    public TripSkeleton buildTripSkeleton(TripPlanningSpecification specification) {
        return buildTripSkeleton(specification, findAvailableZones(specification));
    }

    @Override
    public TripSkeleton buildTripSkeleton(
            TripPlanningSpecification specification,
            List<PlanningZoneSummary> orderedZones
    ) {
        int days = Math.max(1, specification == null ? 1 : specification.days());
        String startTime = specification == null || specification.constraints() == null
                ? "09:00"
                : specification.constraints().preferredStartTime();
        List<TripPlanningSpecification.DayStrategy> strategies = specification == null || specification.dayStrategies() == null
                ? List.of()
                : specification.dayStrategies();
        List<PlanningZoneSummary> zones = orderedZones == null || orderedZones.isEmpty()
                ? findAvailableZones(specification)
                : orderedZones;
        List<TripSkeleton.DaySkeleton> daySkeletons = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            TripPlanningSpecification.DayStrategy strategy = strategyForDay(strategies, day);
            String zoneId = strategy == null ? zoneIdForDay(zones, day) : strategy.primaryZoneId();
            String theme = strategy == null ? "Day " + day + " local highlights" : strategy.theme();
            List<TripSlot> slots = new ArrayList<>();
            int activitySlots = activitySlotsForDay(specification, strategy);
            List<String> activityCapabilities = activityRequiredCapabilities(strategy);
            for (int index = 1; index <= activitySlots; index++) {
                slots.add(new TripSlot(
                        "day" + day + "-activity-" + index,
                        TripSlot.SlotType.ACTIVITY,
                        zoneId,
                        activityCapabilities,
                        activityDurationMinutes(strategy),
                        activityTimeWindow(index, startTime)
                ));
            }
            slots.add(new TripSlot(
                    "day" + day + "-lunch",
                    TripSlot.SlotType.LUNCH,
                    zoneId,
                    List.of(),
                    60,
                    new TripSlot.TimeWindow("12:00", "14:00")
            ));
            TripPlanningSpecification.SpecialEvent specialEvent = specialEventForDay(specification, day);
            if (specialEvent != null) {
                slots.add(new TripSlot(
                        "day" + day + "-special-event",
                        TripSlot.SlotType.SPECIAL_EVENT,
                        zoneId,
                        specialEventCapabilities(specialEvent, strategy),
                        90,
                        specialEventTimeWindow(specialEvent)
                ));
            }
            slots.add(new TripSlot(
                    "day" + day + "-dinner",
                    TripSlot.SlotType.DINNER,
                    zoneId,
                    specialEvent == null ? List.of() : specialEventCapabilities(specialEvent, strategy),
                    90,
                    new TripSlot.TimeWindow("18:00", "20:30")
            ));
            daySkeletons.add(new TripSkeleton.DaySkeleton(day, theme, zoneId, startTime, slots, fallbackZoneIds(strategy)));
        }
        return new TripSkeleton(daySkeletons);
    }

    @Override
    public CoverageResult checkCoverage(
            TripPlanningSpecification specification,
            TripSkeleton skeleton,
            PlaceCandidatePool candidatePool
    ) {
        return checkCoverage(specification, skeleton, candidatePool, List.of());
    }

    @Override
    public CoverageResult checkCoverage(
            TripPlanningSpecification specification,
            TripSkeleton skeleton,
            PlaceCandidatePool candidatePool,
            List<AvailableZoneSummary> availableZoneSummaries
    ) {
        return checkCoverage(specification, skeleton, candidatePool, availableZoneSummaries, findAvailableZones(specification));
    }

    @Override
    public CoverageResult checkCoverage(
            TripPlanningSpecification specification,
            TripSkeleton skeleton,
            PlaceCandidatePool candidatePool,
            List<AvailableZoneSummary> availableZoneSummaries,
            List<PlanningZoneSummary> planningZones
    ) {
        if (skeleton == null || skeleton.days() == null || skeleton.days().isEmpty()) {
            return CoverageResult.sufficient();
        }
        PlaceCandidatePool pool = candidatePool == null ? buildCandidatePool(specification) : candidatePool;
        CoverageInventory inventory = coverageInventoryBuilder.build(
                specification,
                skeleton,
                CatalogInventory.fromCandidatePool(pool),
                availableZoneSummaries == null || availableZoneSummaries.isEmpty()
                        ? findAvailableZoneSummaries(specification, findAvailableZones(specification))
                        : availableZoneSummaries,
                planningZones
        );
        return coverageResultFromInventory(inventory);
    }

    private CoverageResult coverageResultFromInventory(CoverageInventory inventory) {
        List<CoverageGap> hardGaps = new ArrayList<>();
        List<CoverageGap> softGaps = new ArrayList<>();
        for (CoverageInventory.Entry entry : inventory.entries()) {
            CoverageGap gap = new CoverageGap(
                    entry.day(),
                    entry.zoneId(),
                    entry.slotType().name(),
                    entry.requiredCapabilities(),
                    entry.requiredUsageCount(),
                    entry.preferredCandidateCount(),
                    entry.availableCandidateCount()
            );
            if (entry.blocksGeneration() && entry.availableCandidateCount() < entry.requiredUsageCount()) {
                hardGaps.add(gap);
            } else if (entry.blocksGeneration() && entry.availableCandidateCount() < entry.preferredCandidateCount()) {
                softGaps.add(gap);
            }
        }
        return new CoverageResult(hardGaps.isEmpty(), softGaps.isEmpty(), List.copyOf(hardGaps), List.copyOf(softGaps));
    }

    private LocalPoiCatalog loadCatalog(String normalizedCity) {
        String city = displayCity(normalizedCity);
        try {
            LoadedItems hotels = loadItems(normalizedCity, "hotels", "hotel", city);
            LoadedItems attractions = loadItems(normalizedCity, "attractions", "attraction", city);
            LoadedItems restaurants = loadItems(normalizedCity, "restaurants", "restaurant", city);
            String catalogCity = firstNonBlank(hotels.city(), attractions.city(), restaurants.city(), city);
            String country = firstNonBlank(hotels.country(), attractions.country(), restaurants.country(), defaultCountry(catalogCity));
            String state = firstNonBlank(hotels.state(), attractions.state(), restaurants.state(), defaultState(catalogCity));
            String currency = firstNonBlank(hotels.currency(), attractions.currency(), restaurants.currency(), defaultCurrency(country));
            return new LocalPoiCatalog(
                    catalogCity,
                    country,
                    state,
                    currency,
                    hotels.items(),
                    attractions.items(),
                    restaurants.items()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load local POI catalog for " + city, e);
        }
    }

    private LoadedItems loadItems(String citySlug, String datasetType, String defaultType, String defaultCity) throws IOException {
        String filename = resolveDatasetFilename(citySlug, datasetType);
        if (filename == null) {
            return new LoadedItems(List.of(), null, null, null, null);
        }
        ClassPathResource resource = new ClassPathResource(LOCAL_POI_ROOT + filename);
        LocalPoiDataset dataset = objectMapper.readValue(resource.getInputStream(), LocalPoiDataset.class);
        if (dataset.items() != null && !dataset.items().isEmpty()) {
            return new LoadedItems(
                    normalizeItems(dataset.items(), defaultType, defaultCity, dataset.city()),
                    dataset.city(),
                    dataset.country(),
                    dataset.state(),
                    dataset.currency()
            );
        }
        if (dataset.pois() != null && !dataset.pois().isEmpty()) {
            return new LoadedItems(
                    normalizeItems(dataset.pois(), defaultType, defaultCity, dataset.city()),
                    dataset.city(),
                    dataset.country(),
                    dataset.state(),
                    dataset.currency()
            );
        }
        return new LoadedItems(List.of(), dataset.city(), dataset.country(), dataset.state(), dataset.currency());
    }

    private String resolveDatasetFilename(String citySlug, String datasetType) {
        for (String candidate : datasetFilenameCandidates(citySlug, datasetType)) {
            if (new ClassPathResource(LOCAL_POI_ROOT + candidate).exists()) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> datasetFilenameCandidates(String citySlug, String datasetType) {
        List<String> candidates = new ArrayList<>();
        candidates.add(citySlug + "_" + datasetType + ".json");
        if ("brisbane".equals(citySlug)) {
            switch (datasetType) {
                case "hotels" -> candidates.add("brisbane_hotels_8.json");
                case "attractions" -> candidates.add("brisbane_attractions_90.json");
                case "restaurants" -> candidates.add("brisbane_restaurants_72.json");
                default -> {
                }
            }
        }
        return candidates;
    }

    private PlanningZoneSummary toZoneSummary(String city, String area, List<PlaceCandidate> candidates) {
        ZoneCapabilitySummary capabilities = new ZoneCapabilitySummary(
                countType(candidates, "ATTRACTION"),
                countType(candidates, "RESTAURANT"),
                countType(candidates, "HOTEL"),
                countMeal(candidates, "lunch"),
                countMeal(candidates, "dinner"),
                (int) candidates.stream().filter(item -> Boolean.TRUE.equals(item.familyFriendly())).count(),
                countBy(candidates, PlaceCandidate::category),
                candidates.stream()
                        .flatMap(item -> item.styleTags() == null ? java.util.stream.Stream.empty() : item.styleTags().stream())
                        .filter(value -> !isBlank(value))
                        .collect(Collectors.toMap(this::normalizeToken, value -> 1, Integer::sum))
        );
        return new PlanningZoneSummary(
                zoneId(city, area),
                area,
                city,
                "URBAN_DISTRICT",
                themes(capabilities),
                capabilities,
                capabilities.normalDayCapacity() >= 1 ? "FULL_DAY" : "HALF_DAY"
        );
    }

    private PlanningZoneSnapshot toZoneSnapshot(
            PlaceCandidatePool pool,
            PlanningZoneSummary zone
    ) {
        List<PlaceCandidate> attractions = candidatesForZone(pool.attractions(), zone.zoneId());
        List<PlaceCandidate> restaurants = candidatesForZone(pool.restaurants(), zone.zoneId());
        return new PlanningZoneSnapshot(
                zone.zoneId(),
                zone.zoneType(),
                zone.themes(),
                anchorPoiIds(attractions),
                attractions.size(),
                restaurants.size(),
                semanticProfile(zone),
                LOCAL_SNAPSHOT_VERSION,
                Instant.EPOCH.toString()
        );
    }

    private AvailableZoneSummary toAvailableZoneSummary(
            TripPlanningSpecification specification,
            PlaceCandidatePool pool,
            PlanningZoneSummary zone
    ) {
        List<PlaceCandidate> attractions = requestScopedAttractions(
                specification,
                candidatesForZone(pool.attractions(), zone.zoneId())
        );
        List<PlaceCandidate> restaurants = candidatesForZone(pool.restaurants(), zone.zoneId()).stream()
                .filter(item -> budgetAllows(specification, item))
                .toList();
        return new AvailableZoneSummary(
                zone.zoneId(),
                attractions.size(),
                (int) attractions.stream().filter(this::isIndoorCandidate).count(),
                (int) attractions.stream().filter(item -> Boolean.TRUE.equals(item.familyFriendly())).count(),
                (int) restaurants.stream().filter(item -> item.hasMealType("lunch")).count(),
                (int) restaurants.stream().filter(item -> item.hasMealType("dinner")).count(),
                attractions.size() / activitySlotsPerDay(specification),
                LOCAL_FRESHNESS_STATUS
        );
    }

    private List<String> anchorPoiIds(List<PlaceCandidate> attractions) {
        return attractions.stream()
                .sorted(Comparator.comparing((PlaceCandidate item) -> item.priority() == null ? 0 : item.priority()).reversed())
                .map(item -> normalizeToken(item.name()))
                .filter(value -> !value.isBlank())
                .limit(3)
                .toList();
    }

    private String semanticProfile(PlanningZoneSummary zone) {
        List<String> tokens = new ArrayList<>();
        tokens.add(zone.name());
        tokens.addAll(zone.themes());
        tokens.addAll(zone.capabilities().categoryCounts().keySet());
        tokens.addAll(zone.capabilities().styleTagCounts().keySet());
        return tokens.stream()
                .filter(value -> !isBlank(value))
                .map(this::normalizeToken)
                .distinct()
                .collect(Collectors.joining(" "));
    }

    private List<PlaceCandidate> requestScopedAttractions(
            TripPlanningSpecification specification,
            List<PlaceCandidate> attractions
    ) {
        return attractions.stream()
                .filter(item -> !requiresFamilyFriendly(specification) || Boolean.TRUE.equals(item.familyFriendly()))
                .filter(item -> budgetAllows(specification, item))
                .filter(item -> matchesPreferredStyles(specification, item))
                .toList();
    }

    private boolean requiresFamilyFriendly(TripPlanningSpecification specification) {
        return specification != null
                && specification.constraints() != null
                && specification.constraints().familyFriendly();
    }

    private boolean matchesPreferredStyles(TripPlanningSpecification specification, PlaceCandidate candidate) {
        List<String> styles = activityFilteringStyles(specification);
        if (styles.isEmpty()) {
            return true;
        }
        return styles.stream()
                .map(this::normalizeToken)
                .filter(value -> !value.isBlank())
                .anyMatch(style -> candidateHasStyle(candidate, style));
    }

    private List<String> activityFilteringStyles(TripPlanningSpecification specification) {
        List<String> styles = specification == null || specification.styles() == null ? List.of() : specification.styles();
        return styles.stream()
                .map(this::normalizeToken)
                .filter(value -> !value.isBlank())
                .filter(this::isActivityFilteringStyle)
                .toList();
    }

    private boolean isActivityFilteringStyle(String style) {
        return switch (style) {
            case "culture", "museum", "gallery", "art", "nature", "park", "garden", "outdoor",
                 "shopping", "market", "market-shopping", "theme-park", "themepark", "indoor", "rainy-day" -> true;
            default -> false;
        };
    }

    private boolean candidateHasStyle(PlaceCandidate candidate, String style) {
        if (candidate == null) {
            return false;
        }
        String category = normalizeToken(candidate.category());
        if (category.equals(style) || category.contains(style) || style.contains(category)) {
            return true;
        }
        return candidate.styleTags() != null && candidate.styleTags().stream()
                .map(this::normalizeToken)
                .anyMatch(tag -> tag.equals(style) || tag.contains(style) || style.contains(tag));
    }

    private boolean isIndoorCandidate(PlaceCandidate candidate) {
        String category = normalizeToken(candidate == null ? null : candidate.category());
        if (List.of("museum", "gallery", "art-gallery", "shopping", "market", "library").contains(category)) {
            return true;
        }
        return candidate != null && candidate.styleTags() != null && candidate.styleTags().stream()
                .map(this::normalizeToken)
                .anyMatch(tag -> List.of("indoor", "rainy-day", "museum", "gallery").contains(tag));
    }

    private boolean budgetAllows(TripPlanningSpecification specification, PlaceCandidate candidate) {
        Integer budget = specification == null ? null : specification.budget();
        if (budget == null || budget <= 0 || candidate == null || isBlank(candidate.budgetLevel())) {
            return true;
        }
        int dailyBudget = budget / Math.max(1, specification.days());
        String level = normalizeToken(candidate.budgetLevel());
        if (dailyBudget < 100) {
            return "low".equals(level) || "free".equals(level);
        }
        if (dailyBudget < 180) {
            return !"high".equals(level) && !"luxury".equals(level);
        }
        return true;
    }

    private List<String> themes(ZoneCapabilitySummary capabilities) {
        List<String> themes = new ArrayList<>();
        if (capabilities.styleTagCounts().containsKey("culture")) {
            themes.add("culture");
        }
        if (capabilities.styleTagCounts().containsKey("nature")) {
            themes.add("nature");
        }
        if (capabilities.lunchRestaurantCount() + capabilities.dinnerRestaurantCount() > 4) {
            themes.add("local-dining");
        }
        if (capabilities.familyFriendlyCount() > 2) {
            themes.add("family-friendly");
        }
        return themes.isEmpty() ? List.of("local-highlights") : List.copyOf(themes);
    }

    private void checkSlotCoverage(
            TripSkeleton.DaySkeleton day,
            TripSlot.SlotType slotType,
            Map<TripSlot.SlotType, Long> slotCounts,
            List<PlaceCandidate> candidates,
            List<CoverageGap> hardGaps,
            List<CoverageGap> softGaps
    ) {
        int required = slotCounts.getOrDefault(slotType, 0L).intValue();
        if (required <= 0) {
            return;
        }
        int available = candidates == null ? 0 : candidates.size();
        int preferred = Math.max(required + 1, required * 2);
        CoverageGap gap = new CoverageGap(day.day(), day.zoneId(), slotType.name(), List.of(), required, preferred, available);
        if (available < required) {
            hardGaps.add(gap);
        } else if (available < preferred) {
            softGaps.add(gap);
        }
    }

    private List<PlaceCandidate> allCandidates(PlaceCandidatePool pool) {
        if (pool == null) {
            return List.of();
        }
        List<PlaceCandidate> result = new ArrayList<>();
        result.addAll(pool.hotels() == null ? List.of() : pool.hotels());
        result.addAll(pool.attractions() == null ? List.of() : pool.attractions());
        result.addAll(pool.restaurants() == null ? List.of() : pool.restaurants());
        return result;
    }

    private List<PlaceCandidate> candidatesForZone(List<PlaceCandidate> candidates, String zoneId) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
                .filter(item -> sameZone(item.area(), zoneId))
                .toList();
    }

    private List<PlaceCandidate> mealCandidatesForZone(List<PlaceCandidate> candidates, String zoneId, String mealType) {
        return candidatesForZone(candidates, zoneId).stream()
                .filter(item -> item.hasMealType(mealType))
                .toList();
    }

    private int activitySlotsPerDay(TripPlanningSpecification specification) {
        String pace = specification == null ? null : specification.pace();
        int base = switch (normalizeToken(pace)) {
            case "relaxed", "relax", "slow" -> 2;
            case "rush", "fast", "intense" -> 4;
            default -> 3;
        };
        Integer kids = specification == null || specification.party() == null ? null : specification.party().kids();
        return kids != null && kids > 0 ? Math.max(2, base - 1) : base;
    }

    private int activitySlotsForDay(
            TripPlanningSpecification specification,
            TripPlanningSpecification.DayStrategy strategy
    ) {
        int base = activitySlotsPerDay(specification);
        String allocation = normalizeToken(strategy == null ? null : strategy.allocation());
        return switch (allocation) {
            case "half-day", "halfday" -> Math.max(1, Math.min(2, base - 1));
            case "full-or-half-day", "full-half-day" -> Math.max(2, Math.min(base, 3));
            default -> base;
        };
    }

    private List<String> activityRequiredCapabilities(TripPlanningSpecification.DayStrategy strategy) {
        List<String> capabilities = new ArrayList<>();
        if (strategy != null && strategy.requiredCapabilities() != null) {
            capabilities.addAll(strategy.requiredCapabilities());
        }
        if (strategy != null && strategy.preferredPoiTypes() != null) {
            strategy.preferredPoiTypes().stream()
                    .map(this::normalizeToken)
                    .filter(value -> !value.isBlank())
                    .filter(this::isActivitySlotPoiType)
                    .map(value -> "poi-type:" + value)
                    .forEach(capabilities::add);
        }
        return capabilities.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private boolean isActivitySlotPoiType(String poiType) {
        return switch (poiType) {
            case "culture", "museum", "gallery", "art", "nature", "park", "garden", "outdoor",
                 "shopping", "market", "market-shopping", "theme-park", "themepark", "indoor", "rainy-day" -> true;
            default -> false;
        };
    }

    private int activityDurationMinutes(TripPlanningSpecification.DayStrategy strategy) {
        String allocation = normalizeToken(strategy == null ? null : strategy.allocation());
        return switch (allocation) {
            case "half-day", "halfday" -> 75;
            default -> 90;
        };
    }

    private TripSlot.TimeWindow activityTimeWindow(int index, String startTime) {
        if (index == 1 && "10:00".equals(startTime)) {
            return new TripSlot.TimeWindow("10:00", "12:00");
        }
        return switch (index) {
            case 1 -> new TripSlot.TimeWindow("09:00", "11:30");
            case 2 -> new TripSlot.TimeWindow("14:00", "16:00");
            case 3 -> new TripSlot.TimeWindow("16:00", "17:30");
            default -> null;
        };
    }

    private TripPlanningSpecification.SpecialEvent specialEventForDay(TripPlanningSpecification specification, int day) {
        if (specification == null || specification.specialEvents() == null) {
            return null;
        }
        return specification.specialEvents().stream()
                .filter(event -> event != null && event.day() == day)
                .findFirst()
                .orElse(null);
    }

    private List<String> specialEventCapabilities(
            TripPlanningSpecification.SpecialEvent specialEvent,
            TripPlanningSpecification.DayStrategy strategy
    ) {
        List<String> capabilities = new ArrayList<>();
        if (specialEvent != null && specialEvent.requiredCapabilities() != null) {
            capabilities.addAll(specialEvent.requiredCapabilities());
        }
        if (strategy != null && strategy.requiredCapabilities() != null) {
            capabilities.addAll(strategy.requiredCapabilities());
        }
        return capabilities.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private TripSlot.TimeWindow specialEventTimeWindow(TripPlanningSpecification.SpecialEvent specialEvent) {
        String type = normalizeToken(specialEvent == null ? null : specialEvent.type());
        if ("birthday".equals(type)) {
            return new TripSlot.TimeWindow("17:00", "20:30");
        }
        return new TripSlot.TimeWindow("16:00", "19:00");
    }

    private List<String> fallbackZoneIds(TripPlanningSpecification.DayStrategy strategy) {
        return strategy == null || strategy.fallbackZoneIds() == null
                ? List.of()
                : strategy.fallbackZoneIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private TripPlanningSpecification.DayStrategy strategyForDay(List<TripPlanningSpecification.DayStrategy> strategies, int day) {
        return strategies.stream()
                .filter(strategy -> strategy.day() == day)
                .findFirst()
                .orElse(null);
    }

    private String zoneIdForDay(List<PlanningZoneSummary> zones, int day) {
        if (zones == null || zones.isEmpty()) {
            return null;
        }
        List<PlanningZoneSummary> mealCapableZones = zones.stream()
                .filter(zone -> zone.capabilities().attractionCount() > 0)
                .filter(zone -> zone.capabilities().lunchRestaurantCount() > 0)
                .filter(zone -> zone.capabilities().dinnerRestaurantCount() > 0)
                .toList();
        List<PlanningZoneSummary> effectiveZones = mealCapableZones.isEmpty() ? zones : mealCapableZones;
        return effectiveZones.get((day - 1) % effectiveZones.size()).zoneId();
    }

    private boolean sameZone(String area, String zoneId) {
        if (isBlank(zoneId)) {
            return true;
        }
        String normalizedArea = normalizeToken(area);
        String normalizedZone = normalizeToken(zoneId);
        return normalizedArea.equals(normalizedZone) || normalizedZone.endsWith("-" + normalizedArea);
    }

    private String zoneId(String city, String area) {
        String cityPart = normalizeToken(city);
        String areaPart = normalizeToken(area);
        return cityPart.isBlank() ? areaPart : cityPart + "-" + areaPart;
    }

    private int countType(List<PlaceCandidate> candidates, String type) {
        return (int) candidates.stream()
                .filter(item -> item.type() != null && type.equals(item.type().name()))
                .count();
    }

    private int countMeal(List<PlaceCandidate> candidates, String mealType) {
        return (int) candidates.stream()
                .filter(item -> item.hasMealType(mealType))
                .count();
    }

    private Map<String, Integer> countBy(List<PlaceCandidate> candidates, java.util.function.Function<PlaceCandidate, String> classifier) {
        Map<String, Integer> counts = new HashMap<>();
        for (PlaceCandidate candidate : candidates) {
            String key = normalizeToken(classifier.apply(candidate));
            if (!key.isBlank()) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    private List<LocalPoiItem> normalizeItems(
            List<LocalPoiItem> items,
            String defaultType,
            String defaultCity,
            String datasetCity
    ) {
        String fallbackCity = isBlank(datasetCity) ? defaultCity : datasetCity;
        return items.stream()
                .map(item -> new LocalPoiItem(
                        item.name(),
                        isBlank(item.type()) ? defaultType : item.type(),
                        item.category(),
                        isBlank(item.city()) ? fallbackCity : item.city(),
                        item.area(),
                        item.addressLine(),
                        item.latitude(),
                        item.longitude(),
                        item.stayMinutes(),
                        item.styleTags(),
                        item.timeSlots(),
                        item.priority(),
                        item.familyFriendly(),
                        item.budgetLevel(),
                        item.mealTypes(),
                        item.cuisine(),
                        item.source(),
                        item.verificationLevel()
                ))
                .toList();
    }

    private LocalPoiCatalog emptyCatalog(String city) {
        return new LocalPoiCatalog(city, defaultCountry(city), defaultState(city), defaultCurrency(defaultCountry(city)), List.of(), List.of(), List.of());
    }

    private String normalizeCity(String city) {
        return city == null
                ? ""
                : city.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeToken(String value) {
        return value == null
                ? ""
                : value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private String displayCity(String normalizedCity) {
        if ("brisbane".equals(normalizedCity)) {
            return "Brisbane";
        }
        if (normalizedCity == null || normalizedCity.isBlank()) {
            return "";
        }
        String[] parts = normalizedCity.split("_+");
        List<String> displayParts = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                displayParts.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
            }
        }
        return String.join(" ", displayParts);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String defaultCountry(String city) {
        return "Brisbane".equalsIgnoreCase(city) ? "Australia" : "";
    }

    private String defaultState(String city) {
        return "Brisbane".equalsIgnoreCase(city) ? "QLD" : "";
    }

    private String defaultCurrency(String country) {
        return "Australia".equalsIgnoreCase(country) ? "AUD" : "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LocalPoiDataset(
            String city,
            String country,
            String state,
            String currency,
            String datasetType,
            List<LocalPoiItem> items,
            List<LocalPoiItem> pois
    ) {}

    private record LoadedItems(
            List<LocalPoiItem> items,
            String city,
            String country,
            String state,
            String currency
    ) {}
}
