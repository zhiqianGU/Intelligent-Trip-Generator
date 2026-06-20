package thesis.project.gu.planning.localfast;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import thesis.project.gu.catalog.application.DestinationCatalog;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.application.ItineraryGenerator;
import thesis.project.gu.planning.domain.PlaceCandidate;
import thesis.project.gu.planning.domain.PlaceCandidatePool;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.domain.PlanningContextVersion;
import thesis.project.gu.planning.domain.TripSkeleton;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.routing.application.LocalRouteEstimateService;
import thesis.project.gu.routing.domain.EstimatedRouteSegment;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LocalPlanGeneratorService implements ItineraryGenerator {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DAY_START_MINUTES = 9 * 60;
    private static final int LUNCH_START_MINUTES = 12 * 60 + 15;
    private static final int LUNCH_LATEST_START_MINUTES = 13 * 60 + 30;
    private static final int DINNER_START_MINUTES = 17 * 60 + 30;
    private static final int DINNER_LATEST_START_MINUTES = 20 * 60 + 30;
    private static final double MEAL_HARD_DISTANCE_KM = 5.0;

    private final DestinationCatalog catalogService;
    private final SlotFillingScheduler slotFillingScheduler;
    private final LocalRouteEstimateService localRouteEstimateService;

    public LocalPlanGeneratorService(DestinationCatalog catalogService) {
        this(catalogService, new SlotFillingScheduler(), new LocalRouteEstimateService());
    }

    public LocalPlanGeneratorService(DestinationCatalog catalogService, SlotFillingScheduler slotFillingScheduler) {
        this(catalogService, slotFillingScheduler, new LocalRouteEstimateService());
    }

    @Autowired
    public LocalPlanGeneratorService(
            DestinationCatalog catalogService,
            SlotFillingScheduler slotFillingScheduler,
            LocalRouteEstimateService localRouteEstimateService
    ) {
        this.catalogService = catalogService;
        this.slotFillingScheduler = slotFillingScheduler == null ? new SlotFillingScheduler() : slotFillingScheduler;
        this.localRouteEstimateService = localRouteEstimateService == null ? new LocalRouteEstimateService() : localRouteEstimateService;
    }

    public PlanDraftResponse generate(CreatePlanReq req) {
        TripPlanningSpecification specification = TripPlanningSpecification.fromRequest(req);
        return generateDraft(req, catalogService.buildCandidatePool(specification), null).toResponse();
    }

    @Override
    public PlanDraft generate(TripPlanningSpecification specification, PlaceCandidatePool candidatePool) {
        return generate(specification, candidatePool, catalogService.buildTripSkeleton(specification));
    }

    @Override
    public PlanDraft generate(TripPlanningSpecification specification, PlaceCandidatePool candidatePool, TripSkeleton skeleton) {
        CreatePlanReq req = specification == null ? null : specification.toRequest();
        PlaceCandidatePool effectivePool = candidatePool == null
                ? catalogService.buildCandidatePool(specification)
                : candidatePool;
        return generateDraft(req, effectivePool, skeleton);
    }

    private PlanDraft generateDraft(CreatePlanReq req, PlaceCandidatePool candidatePool, TripSkeleton skeleton) {
        PlaceCandidatePool pool = candidatePool == null ? PlaceCandidatePool.empty(req == null ? null : req.city()) : candidatePool;
        if (pool.totalItemCount() == 0) {
            throw new IllegalArgumentException("No local POI catalog is available for city: " + (req == null ? null : req.city()));
        }

        int days = Math.max(1, req == null ? 1 : req.days());
        String pace = normalizePace(req == null ? null : req.pace());
        PlaceCandidate hotel = chooseHotel(pool.hotels(), req);
        List<PlanDraft.DayPlan> dayPlans = new ArrayList<>();
        List<SlotFillingScheduler.DaySchedule> slotSchedules = slotFillingScheduler.schedule(req, pool, skeleton);
        if (slotSchedules.size() == days) {
            for (SlotFillingScheduler.DaySchedule schedule : slotSchedules) {
                List<PlanDraft.Place> stops = scheduleDayStops(schedule.attractions(), schedule.lunch(), schedule.dinner(), schedule.day());
                String anchorArea = schedule.anchorArea();
                dayPlans.add(new PlanDraft.DayPlan(
                        schedule.day(),
                        toHotelPlace(hotel),
                        stops,
                        dayTheme(schedule.day(), anchorArea, schedule.attractions()),
                        morningNote(schedule.attractions()),
                        afternoonNote(schedule.attractions()),
                        eveningNote(schedule.dinner()),
                        dayNote(schedule.day(), anchorArea, schedule.attractions())
                ));
            }
        } else {
            dayPlans.addAll(generateAreaAwareFallbackDays(req, pool, skeleton, hotel, days, pace));
        }

        return new PlanDraft(
                pool.city(),
                pool.country(),
                days,
                pool.currency(),
                toPlanDraftParty(req == null ? new CreatePlanReq.Party(2, 0) : req.party()),
                pace,
                pool.city() + " " + days + "-Day Local Fast Itinerary",
                "A fast local itinerary generated from curated local POI data with area-aware daily routing.",
                dayPlans,
                "local-fast",
                "ESTIMATED",
                "READY",
                "ZONE_GUIDED_LOCAL_FIRST",
                "SUFFICIENT",
                "BASIC",
                "PENDING",
                List.of(),
                PlanningContextVersion.localFirst(),
                PlanningContextVersion.INITIAL_PLAN_VERSION,
                ""
        );
    }

    private List<PlanDraft.DayPlan> generateAreaAwareFallbackDays(
            CreatePlanReq req,
            PlaceCandidatePool pool,
            TripSkeleton skeleton,
            PlaceCandidate hotel,
            int days,
            String pace
    ) {
        Set<String> usedAttractions = new HashSet<>();
        Set<String> usedRestaurants = new HashSet<>();
        List<PlanDraft.DayPlan> dayPlans = new ArrayList<>();
        int nonMealTarget = nonMealStopsPerDay(pace, req == null ? null : req.party());
        List<String> areaRotation = areaRotation(pool, nonMealTarget, skeleton);
        for (int dayIndex = 1; dayIndex <= days; dayIndex++) {
            String anchorArea = areaRotation.get((dayIndex - 1) % areaRotation.size());
            List<PlaceCandidate> attractions = selectAttractionsForDay(
                    pool.attractions(),
                    req,
                    anchorArea,
                    nonMealTarget,
                    usedAttractions
            );
            attractions.forEach(item -> usedAttractions.add(identityKey(item)));

            PlaceCandidate lunch = selectRestaurant(pool.restaurants(), attractions, "lunch", req, usedRestaurants);
            usedRestaurants.add(identityKey(lunch));
            PlaceCandidate dinner = selectRestaurant(pool.restaurants(), attractions, "dinner", req, usedRestaurants);
            usedRestaurants.add(identityKey(dinner));

            List<PlanDraft.Place> stops = scheduleDayStops(attractions, lunch, dinner, dayIndex);
            String theme = dayTheme(dayIndex, anchorArea, attractions);
            dayPlans.add(new PlanDraft.DayPlan(
                    dayIndex,
                    toHotelPlace(hotel),
                    stops,
                    theme,
                    morningNote(attractions),
                    afternoonNote(attractions),
                    eveningNote(dinner),
                    dayNote(dayIndex, anchorArea, attractions)
            ));
        }
        return dayPlans;
    }

    private List<PlaceCandidate> selectAttractionsForDay(
            List<PlaceCandidate> attractions,
            CreatePlanReq req,
            String anchorArea,
            int targetCount,
            Set<String> used
    ) {
        List<PlaceCandidate> selected = attractions.stream()
                .filter(item -> !used.contains(identityKey(item)))
                .filter(item -> sameArea(item.area(), anchorArea))
                .sorted(Comparator
                        .comparingInt((PlaceCandidate item) -> attractionScore(item, req, anchorArea)).reversed()
                        .thenComparing(PlaceCandidate::name))
                .limit(targetCount)
                .toList();
        if (selected.size() >= targetCount) {
            return selected;
        }
        List<PlaceCandidate> fallback = new ArrayList<>(selected);
        Set<String> selectedKeys = fallback.stream().map(this::identityKey).collect(Collectors.toSet());
        attractions.stream()
                .filter(item -> !used.contains(identityKey(item)))
                .filter(item -> !selectedKeys.contains(identityKey(item)))
                .sorted(Comparator
                        .comparingInt((PlaceCandidate item) -> fallbackAttractionScore(item, req, fallback)).reversed()
                        .thenComparing(PlaceCandidate::name))
                .limit(targetCount - selected.size())
                .forEach(fallback::add);
        return fallback;
    }

    private int fallbackAttractionScore(PlaceCandidate item, CreatePlanReq req, List<PlaceCandidate> selected) {
        int score = safePriority(item) * 3;
        if (matchesRequestedStyle(item, req)) {
            score += 40;
        }
        if (hasKids(req) && Boolean.TRUE.equals(item.familyFriendly())) {
            score += 20;
        }
        if (hasKids(req) && !Boolean.TRUE.equals(item.familyFriendly())) {
            score -= 80;
        }
        double nearestSelectedKm = nearestDistanceKm(item, selected);
        if (nearestSelectedKm <= 2.0) {
            score += 160;
        } else if (nearestSelectedKm <= 5.0) {
            score += 100;
        } else if (nearestSelectedKm <= 8.0) {
            score += 40;
        } else if (nearestSelectedKm < Double.MAX_VALUE) {
            score -= 120;
        }
        return score;
    }

    private int attractionScore(PlaceCandidate item, CreatePlanReq req, String anchorArea) {
        int score = safePriority(item) * 3;
        if (sameArea(item.area(), anchorArea)) {
            score += 80;
        }
        if (matchesRequestedStyle(item, req)) {
            score += 60;
        }
        if (hasKids(req) && Boolean.TRUE.equals(item.familyFriendly())) {
            score += 25;
        }
        if (hasKids(req) && !Boolean.TRUE.equals(item.familyFriendly())) {
            score -= 80;
        }
        return score;
    }

    private PlaceCandidate selectRestaurant(
            List<PlaceCandidate> restaurants,
            List<PlaceCandidate> attractions,
            String mealType,
            CreatePlanReq req,
            Set<String> used
    ) {
        Set<String> dayAreas = attractions.stream()
                .map(PlaceCandidate::area)
                .filter(area -> area != null && !area.isBlank())
                .map(this::normalize)
                .collect(Collectors.toCollection(HashSet::new));

        List<PlaceCandidate> candidates = restaurants.stream()
                .filter(item -> item.hasMealType(mealType))
                .filter(item -> !used.contains(identityKey(item)))
                .sorted(Comparator
                        .comparingInt((PlaceCandidate item) -> restaurantScore(item, req, attractions, dayAreas)).reversed()
                        .thenComparing(PlaceCandidate::name))
                .toList();
        List<PlaceCandidate> nearbyCandidates = candidates.stream()
                .filter(item -> nearestDistanceKm(item, attractions) <= MEAL_HARD_DISTANCE_KM)
                .toList();
        if (!nearbyCandidates.isEmpty()) {
            return nearbyCandidates.getFirst();
        }
        if (!candidates.isEmpty()) {
            return candidates.getFirst();
        }
        List<PlaceCandidate> reusableCandidates = restaurants.stream()
                .filter(item -> item.hasMealType(mealType))
                .sorted(Comparator.comparingInt((PlaceCandidate item) -> restaurantScore(item, req, attractions, dayAreas)).reversed())
                .toList();
        return reusableCandidates.stream()
                .filter(item -> nearestDistanceKm(item, attractions) <= MEAL_HARD_DISTANCE_KM)
                .findFirst()
                .orElseGet(() -> reusableCandidates.stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No local restaurant supports " + mealType)));
    }

    private int restaurantScore(PlaceCandidate item, CreatePlanReq req, List<PlaceCandidate> attractions, Set<String> dayAreas) {
        int score = safePriority(item) * 3;
        if (dayAreas.contains(normalize(item.area()))) {
            score += 80;
        }
        double nearestAttractionKm = nearestDistanceKm(item, attractions);
        if (nearestAttractionKm <= 1.5) {
            score += 120;
        } else if (nearestAttractionKm <= 3.0) {
            score += 80;
        } else if (nearestAttractionKm <= 5.0) {
            score += 40;
        } else if (nearestAttractionKm > 8.0) {
            score -= 80;
        }
        if (hasKids(req) && Boolean.TRUE.equals(item.familyFriendly())) {
            score += 20;
        }
        if (hasKids(req) && !Boolean.TRUE.equals(item.familyFriendly())) {
            score -= 100;
        }
        String preferredBudget = preferredBudgetLevel(req);
        if (!preferredBudget.isBlank() && preferredBudget.equals(normalize(item.budgetLevel()))) {
            score += 20;
        }
        return score;
    }

    private List<PlanDraft.Place> scheduleDayStops(
            List<PlaceCandidate> attractions,
            PlaceCandidate lunch,
            PlaceCandidate dinner,
            int dayIndex
    ) {
        List<PlanDraft.Place> stops = new ArrayList<>();
        int current = DAY_START_MINUTES;
        boolean lunchInserted = false;
        PlaceCandidate previous = null;
        int scheduledAttractions = 0;
        for (int i = 0; i < attractions.size(); i++) {
            PlaceCandidate attraction = attractions.get(i);
            int stay = stayMinutes(attraction);
            if (shouldSkipAfternoonStopToProtectDinner(current, previous, attraction, dinner, stay, lunchInserted, scheduledAttractions)) {
                continue;
            }
            if (!lunchInserted && shouldInsertLunchBeforeNextStop(current, previous, attraction, lunch, stay)) {
                EstimatedRouteSegment lunchTransfer = estimateTransfer(previous, lunch);
                current += transferMinutes(lunchTransfer);
                current = Math.max(current, LUNCH_START_MINUTES);
                stops.add(toMealPlace(lunch, "lunch", current, dayIndex, lunchTransfer));
                current += stayMinutes(lunch);
                previous = lunch;
                lunchInserted = true;
            }
            EstimatedRouteSegment attractionTransfer = estimateTransfer(previous, attraction);
            current += transferMinutes(attractionTransfer);
            stops.add(toAttractionPlace(attraction, lunchInserted ? "afternoon" : "morning", current, stay, dayIndex, attractionTransfer));
            current += stay;
            previous = attraction;
            scheduledAttractions++;
        }
        if (!lunchInserted) {
            EstimatedRouteSegment lunchTransfer = estimateTransfer(previous, lunch);
            current += transferMinutes(lunchTransfer);
            current = Math.max(current, LUNCH_START_MINUTES);
            stops.add(toMealPlace(lunch, "lunch", current, dayIndex, lunchTransfer));
            current += stayMinutes(lunch);
            previous = lunch;
        }
        EstimatedRouteSegment dinnerTransfer = estimateTransfer(previous, dinner);
        current += transferMinutes(dinnerTransfer);
        current = Math.max(current, DINNER_START_MINUTES);
        stops.add(toMealPlace(dinner, "dinner", current, dayIndex, dinnerTransfer));
        return stops;
    }

    private boolean shouldSkipAfternoonStopToProtectDinner(
            int currentMinutes,
            PlaceCandidate previous,
            PlaceCandidate next,
            PlaceCandidate dinner,
            int nextStayMinutes,
            boolean lunchInserted,
            int scheduledAttractions
    ) {
        if (!lunchInserted || scheduledAttractions < 2) {
            return false;
        }
        int projectedDinnerStart = currentMinutes
                + transferMinutes(previous, next)
                + nextStayMinutes
                + transferMinutes(next, dinner);
        return projectedDinnerStart > DINNER_LATEST_START_MINUTES;
    }

    private boolean shouldInsertLunchBeforeNextStop(
            int currentMinutes,
            PlaceCandidate previous,
            PlaceCandidate next,
            PlaceCandidate lunch,
            int nextStayMinutes
    ) {
        if (currentMinutes >= LUNCH_START_MINUTES) {
            return true;
        }
        return currentMinutes + transferMinutes(previous, next) + nextStayMinutes + transferMinutes(next, lunch) > LUNCH_LATEST_START_MINUTES;
    }

    private PlanDraft.Place toHotelPlace(PlaceCandidate item) {
        return toPlace(item, "hotel", "night", null, null, 0,
                item.name() + " is used as a stable base for the local fast itinerary.",
                "Confirm current rates and availability before booking.");
    }

    private PlanDraft.Place toAttractionPlace(
            PlaceCandidate item,
            String slot,
            int start,
            int stay,
            int dayIndex,
            EstimatedRouteSegment transfer
    ) {
        int safeStart = safeStartForStay(start, stay);
        return toPlace(item, normalizeCategory(item.category()), slot, formatMinutes(safeStart), formatMinutes(safeStart + stay), stay,
                item.name() + " anchors Day " + dayIndex + " around " + item.area() + " with a verified local stop.",
                appendRouteEstimate("Keep the visit flexible around weather and daily pace.", transfer));
    }

    private PlanDraft.Place toMealPlace(
            PlaceCandidate item,
            String mealType,
            int start,
            int dayIndex,
            EstimatedRouteSegment transfer
    ) {
        int stay = stayMinutes(item);
        int safeStart = safeStartForStay(start, stay);
        return toPlace(item, "restaurant", mealType, formatMinutes(safeStart), formatMinutes(safeStart + stay), stay,
                item.name() + " provides a practical " + mealType + " break for Day " + dayIndex + " near " + item.area() + ".",
                appendRouteEstimate("Keep the meal timing flexible around the surrounding stops.", transfer));
    }

    private PlanDraft.Place toPlace(
            PlaceCandidate item,
            String category,
            String timeSlot,
            String startTime,
            String endTime,
            Integer stayMinutes,
            String reason,
            String tip
    ) {
        return new PlanDraft.Place(
                item.name(),
                item.addressLine(),
                item.area(),
                item.city(),
                catalogState(item),
                null,
                catalogCountry(item),
                category,
                stayMinutes,
                timeSlot,
                startTime,
                endTime,
                "lunch".equals(timeSlot) || "dinner".equals(timeSlot) ? timeSlot : null,
                item.area(),
                item.cuisine(),
                null,
                item.budgetLevel(),
                reason,
                tip,
                null,
                null,
                "OPERATIONAL",
                null,
                item.latitude(),
                item.longitude()
        );
    }

    private PlanDraft.Party toPlanDraftParty(CreatePlanReq.Party party) {
        return party == null ? null : new PlanDraft.Party(party.adults(), party.kids());
    }

    private PlaceCandidate chooseHotel(List<PlaceCandidate> hotels, CreatePlanReq req) {
        return hotels.stream()
                .sorted(Comparator
                        .comparingInt((PlaceCandidate item) -> hotelScore(item, req)).reversed()
                        .thenComparing(PlaceCandidate::name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Local catalog has no hotels"));
    }

    private int hotelScore(PlaceCandidate item, CreatePlanReq req) {
        int score = safePriority(item) * 3;
        String preferredBudget = preferredBudgetLevel(req);
        if (!preferredBudget.isBlank() && preferredBudget.equals(normalize(item.budgetLevel()))) {
            score += 50;
        }
        return score;
    }

    private List<String> areaRotation(PlaceCandidatePool catalog, int targetStopsPerDay, TripSkeleton skeleton) {
        List<String> skeletonAreas = skeletonAreas(catalog, skeleton);
        if (!skeletonAreas.isEmpty()) {
            return skeletonAreas;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlaceCandidate item : catalog.attractions()) {
            if (item.area() == null || item.area().isBlank()) {
                continue;
            }
            counts.merge(item.area(), 1, Integer::sum);
        }
        List<AreaSlot> slots = counts.entrySet().stream()
                .map(entry -> new AreaSlot(entry.getKey(), Math.max(0, entry.getValue() / Math.max(1, targetStopsPerDay))))
                .filter(slot -> slot.remainingDays() > 0)
                .sorted(Comparator.comparingInt(AreaSlot::remainingDays).reversed().thenComparing(AreaSlot::area))
                .toList();
        List<String> rotation = balancedAreaRotation(slots);
        if (!rotation.isEmpty()) {
            return rotation;
        }
        List<String> areas = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        return areas.isEmpty() ? List.of(catalog.city()) : areas;
    }

    private List<String> skeletonAreas(PlaceCandidatePool catalog, TripSkeleton skeleton) {
        if (catalog == null || skeleton == null || skeleton.days() == null || skeleton.days().isEmpty()) {
            return List.of();
        }
        List<String> knownAreas = catalog.attractions() == null ? List.of() : catalog.attractions().stream()
                .map(PlaceCandidate::area)
                .filter(area -> area != null && !area.isBlank())
                .distinct()
                .toList();
        List<String> areas = new ArrayList<>();
        for (TripSkeleton.DaySkeleton day : skeleton.days()) {
            String area = areaForZoneId(day.zoneId(), knownAreas);
            if (area != null && !area.isBlank()) {
                areas.add(area);
            }
        }
        return areas;
    }

    private String areaForZoneId(String zoneId, List<String> knownAreas) {
        if (zoneId == null || zoneId.isBlank() || knownAreas == null) {
            return null;
        }
        String normalizedZone = normalize(zoneId).replace("_", "-");
        return knownAreas.stream()
                .filter(area -> normalizedZone.endsWith(normalize(area).replace(" ", "-").replace("_", "-")))
                .findFirst()
                .orElse(null);
    }

    private List<String> balancedAreaRotation(List<AreaSlot> slots) {
        List<AreaSlot> mutable = slots.stream()
                .map(slot -> new AreaSlot(slot.area(), slot.remainingDays()))
                .collect(Collectors.toCollection(ArrayList::new));
        List<String> result = new ArrayList<>();
        while (mutable.stream().anyMatch(slot -> slot.remainingDays() > 0)) {
            mutable.sort(Comparator.comparingInt(AreaSlot::remainingDays).reversed().thenComparing(AreaSlot::area));
            int selectedIndex = -1;
            for (int i = 0; i < mutable.size(); i++) {
                AreaSlot candidate = mutable.get(i);
                if (candidate.remainingDays() <= 0) {
                    continue;
                }
                if (result.isEmpty() || !shouldAvoidAdjacentArea(result.getLast(), candidate.area())) {
                    selectedIndex = i;
                    break;
                }
            }
            if (selectedIndex < 0) {
                selectedIndex = 0;
            }
            AreaSlot selected = mutable.get(selectedIndex);
            if (selected.remainingDays() <= 0) {
                break;
            }
            result.add(selected.area());
            mutable.set(selectedIndex, new AreaSlot(selected.area(), selected.remainingDays() - 1));
            mutable.removeIf(slot -> slot.remainingDays() <= 0);
        }
        return result;
    }

    private boolean shouldAvoidAdjacentArea(String previousArea, String candidateArea) {
        if (sameArea(previousArea, candidateArea)) {
            return true;
        }
        return isCoreUrbanArea(previousArea) && isCoreUrbanArea(candidateArea);
    }

    private boolean isCoreUrbanArea(String area) {
        String normalized = normalize(area);
        return "brisbane cbd".equals(normalized)
                || "south bank".equals(normalized);
    }

    private String dayTheme(int dayIndex, String area, List<PlaceCandidate> attractions) {
        String categories = attractions.stream()
                .map(item -> displayCategory(normalizeCategory(item.category())))
                .distinct()
                .limit(2)
                .collect(Collectors.joining(" and "));
        return "Day " + dayIndex + " " + area + " " + (categories.isBlank() ? "local highlights" : categories);
    }

    private String morningNote(List<PlaceCandidate> attractions) {
        return attractions.isEmpty()
                ? "Start with a flexible local morning."
                : "Start around " + attractions.getFirst().area() + " with " + attractions.getFirst().name() + ".";
    }

    private String afternoonNote(List<PlaceCandidate> attractions) {
        if (attractions.size() < 2) {
            return "Keep the afternoon flexible around the confirmed stops.";
        }
        PlaceCandidate item = attractions.get(attractions.size() - 1);
        return "Continue with " + item.name() + " while keeping the route compact.";
    }

    private String eveningNote(PlaceCandidate dinner) {
        return "Finish with " + dinner.name() + " as the planned dinner stop.";
    }

    private String dayNote(int dayIndex, String area, List<PlaceCandidate> attractions) {
        String names = attractions.stream().map(PlaceCandidate::name).limit(2).collect(Collectors.joining(" and "));
        return "Day " + dayIndex + " focuses on " + area + (names.isBlank() ? "." : " around " + names + ".");
    }

    private int nonMealStopsPerDay(String pace, CreatePlanReq.Party party) {
        int base = switch (pace) {
            case "relaxed" -> 2;
            case "rush" -> 4;
            default -> 3;
        };
        int kids = party == null || party.kids() == null ? 0 : party.kids();
        return kids > 0 ? Math.max(2, base - 1) : base;
    }

    private String normalizePace(String pace) {
        String normalized = normalize(pace).replace("_", "-").replace(" ", "-");
        return switch (normalized) {
            case "relax", "relaxed", "slow" -> "relaxed";
            case "rush", "fast", "fast-pace", "fastpaced", "intense" -> "rush";
            default -> "normal";
        };
    }

    private String preferredBudgetLevel(CreatePlanReq req) {
        Integer budget = req == null ? null : req.budget();
        if (budget == null || budget <= 0) {
            return "";
        }
        if (budget < 800) {
            return "low";
        }
        if (budget < 2000) {
            return "medium";
        }
        return "high";
    }

    private boolean matchesRequestedStyle(PlaceCandidate item, CreatePlanReq req) {
        if (req == null || req.style() == null || req.style().isEmpty()) {
            return false;
        }
        return req.style().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(this::normalizeStyle)
                .anyMatch(item::hasStyleTag);
    }

    private String normalizeStyle(String style) {
        return switch (normalize(style)) {
            case "market_shopping", "shopping", "market" -> "shopping";
            case "theme_park", "theme-park" -> "theme_park";
            default -> normalize(style);
        };
    }

    private boolean hasKids(CreatePlanReq req) {
        return req != null && req.party() != null && req.party().kids() != null && req.party().kids() > 0;
    }

    private boolean sameArea(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        return !a.isBlank() && (a.equals(b) || a.contains(b) || b.contains(a));
    }

    private int stayMinutes(PlaceCandidate item) {
        return item == null || item.stayMinutes() == null || item.stayMinutes() <= 0 ? 60 : item.stayMinutes();
    }

    private int safePriority(PlaceCandidate item) {
        return item == null || item.priority() == null ? 50 : item.priority();
    }

    private double nearestDistanceKm(PlaceCandidate item, List<PlaceCandidate> others) {
        if (item == null || item.latitude() == null || item.longitude() == null || others == null || others.isEmpty()) {
            return Double.MAX_VALUE;
        }
        return others.stream()
                .filter(other -> other != null && other.latitude() != null && other.longitude() != null)
                .mapToDouble(other -> distanceKm(item.latitude(), item.longitude(), other.latitude(), other.longitude()))
                .min()
                .orElse(Double.MAX_VALUE);
    }

    private int transferMinutes(PlaceCandidate from, PlaceCandidate to) {
        return transferMinutes(estimateTransfer(from, to));
    }

    private EstimatedRouteSegment estimateTransfer(PlaceCandidate from, PlaceCandidate to) {
        return localRouteEstimateService.estimate(from, to);
    }

    private int transferMinutes(EstimatedRouteSegment segment) {
        return segment == null || segment.durationMinutes() == null ? 0 : Math.max(0, segment.durationMinutes());
    }

    private String appendRouteEstimate(String tip, EstimatedRouteSegment segment) {
        if (segment == null || segment.durationMinutes() == null || segment.durationMinutes() <= 0) {
            return tip;
        }
        String distance = segment.distanceMeters() == null
                ? ""
                : ", about " + Math.max(1, (int) Math.round(segment.distanceMeters() / 1000.0)) + " km";
        return tip + " Route status: " + segment.routeStatus()
                + ", estimated " + segment.durationMinutes() + " min"
                + distance + " by " + segment.mode() + ".";
    }

    private double distanceKm(double leftLatitude, double leftLongitude, double rightLatitude, double rightLongitude) {
        double lat1 = Math.toRadians(leftLatitude);
        double lat2 = Math.toRadians(rightLatitude);
        double deltaLat = Math.toRadians(rightLatitude - leftLatitude);
        double deltaLon = Math.toRadians(rightLongitude - leftLongitude);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String normalizeCategory(String category) {
        String normalized = normalize(category);
        return switch (normalized) {
            case "art_gallery" -> "gallery";
            case "botanic_garden", "nature_reserve", "urban_beach", "coastal_park", "wetland", "arboretum" -> "park";
            case "brewery_restaurant", "bar_restaurant", "steakhouse", "food_precinct" -> "restaurant";
            case "serviced_apartment" -> "hotel";
            default -> normalized.isBlank() ? "attraction" : normalized;
        };
    }

    private String displayCategory(String category) {
        return category == null ? "" : category.replace("_", " ");
    }

    private String catalogState(PlaceCandidate item) {
        PlaceCandidatePool candidatePool = candidatePoolForCity(item == null ? null : item.city());
        return candidatePool.state();
    }

    private String catalogCountry(PlaceCandidate item) {
        PlaceCandidatePool candidatePool = candidatePoolForCity(item == null ? null : item.city());
        return candidatePool.country();
    }

    private PlaceCandidatePool candidatePoolForCity(String city) {
        return catalogService.buildCandidatePool(new TripPlanningSpecification(
                new TripPlanningSpecification.Destination(city),
                1,
                null,
                null,
                List.of(),
                "normal",
                null,
                null
        ));
    }

    private String identityKey(PlaceCandidate item) {
        return normalize(item == null ? null : item.name()) + "|" + normalize(item == null ? null : item.addressLine());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String formatMinutes(int minutes) {
        int clamped = Math.max(0, Math.min(minutes, 23 * 60 + 59));
        return LocalTime.of(clamped / 60, clamped % 60).format(TIME_FORMATTER);
    }

    private int safeStartForStay(int startMinutes, int stayMinutes) {
        return Math.max(0, Math.min(startMinutes, 23 * 60 + 59 - Math.max(1, stayMinutes)));
    }

    private record AreaSlot(String area, int remainingDays) {}
}
