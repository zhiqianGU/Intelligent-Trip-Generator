package thesis.project.gu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.local.LocalPoiCatalog;
import thesis.project.gu.catalog.local.LocalPoiItem;
import thesis.project.gu.catalog.local.LocalPoiCatalogService;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.localfast.LocalPlanGeneratorService;
import thesis.project.gu.planning.quality.LocalPlanQualityDiagnosticService;
import thesis.project.gu.planning.quality.LocalPlanQualityReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class Rq2QualityExperimentTest {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final LocalPoiCatalogService catalogService = new LocalPoiCatalogService(new ObjectMapper());
    private final LocalPlanGeneratorService localPlanGeneratorService = new LocalPlanGeneratorService(catalogService);
    private final LocalPlanQualityDiagnosticService diagnosticService = new LocalPlanQualityDiagnosticService();

    @Test
    void exportRq2QualityComparison() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("rq2.experiment"), "Set -Drq2.experiment=true to export RQ2 data.");

        List<Integer> daysCases = List.of(5, 10, 15, 20);
        List<String> paces = List.of("normal");
        List<String> rows = new ArrayList<>();
        rows.add(String.join(",",
                "mode",
                "city",
                "days",
                "pace",
                "kids",
                "score",
                "error_count",
                "warning_count",
                "total_issues",
                "late_meals",
                "duplicate_pois",
                "far_restaurants",
                "long_transfers",
                "time_overlaps",
                "density_warnings",
                "theme_area_warnings"
        ));

        for (String pace : paces) {
            for (int days : daysCases) {
                CreatePlanReq req = new CreatePlanReq(
                        "Brisbane",
                        days,
                        2000,
                        new CreatePlanReq.Party(2, 0),
                        List.of("culture", "nature"),
                        pace,
                        "local-fast",
                        "2026-06-01"
                );
                PlanDraftResponse proposed = localPlanGeneratorService.generate(req);
                PlanDraftResponse priorityOnly = generatePriorityOnly(req);

                rows.add(toCsvRow("area-aware heuristic", req, diagnosticService.diagnoseResponse(proposed)));
                rows.add(toCsvRow("priority-only", req, diagnosticService.diagnoseResponse(priorityOnly)));
            }
        }

        Path outputDir = Path.of("scripts", "results");
        Files.createDirectories(outputDir);
        Path output = outputDir.resolve("rq2-quality-" + FILE_TIME.format(LocalDateTime.now()) + ".csv");
        Files.write(output, rows, StandardCharsets.UTF_8);
        System.out.println("Saved RQ2 quality data to " + output.toAbsolutePath());
    }

    private PlanDraftResponse generatePriorityOnly(CreatePlanReq req) {
        LocalPoiCatalog catalog = catalogService.catalogForCity(req.city());
        int days = Math.max(1, req.days());
        int target = nonMealStopsPerDay(req.pace(), req.party());
        LocalPoiItem hotel = catalog.hotels().stream()
                .max(Comparator.comparingInt(this::priority))
                .orElse(null);
        List<LocalPoiItem> attractions = catalog.attractions().stream()
                .sorted(Comparator.comparingInt(this::priority).reversed().thenComparing(LocalPoiItem::name))
                .toList();
        List<LocalPoiItem> lunchRestaurants = restaurantsFor(catalog, "lunch");
        List<LocalPoiItem> dinnerRestaurants = restaurantsFor(catalog, "dinner");

        List<PlanDraftResponse.DayPlan> dayPlans = new ArrayList<>();
        int attractionCursor = 0;
        for (int day = 1; day <= days; day++) {
            List<LocalPoiItem> dayAttractions = new ArrayList<>();
            for (int i = 0; i < target && !attractions.isEmpty(); i++) {
                dayAttractions.add(attractions.get(attractionCursor % attractions.size()));
                attractionCursor++;
            }
            LocalPoiItem lunch = lunchRestaurants.isEmpty() ? null : lunchRestaurants.get((day - 1) % lunchRestaurants.size());
            LocalPoiItem dinner = dinnerRestaurants.isEmpty() ? null : dinnerRestaurants.get((day - 1) % dinnerRestaurants.size());

            List<PlanDraftResponse.Place> stops = schedulePriorityOnly(dayAttractions, lunch, dinner);
            String theme = "Day " + day + " priority-only highlights";
            dayPlans.add(new PlanDraftResponse.DayPlan(
                    day,
                    toPlace(hotel, "hotel", null, null, null),
                    stops,
                    theme,
                    "Priority-only morning selection.",
                    "Priority-only afternoon selection.",
                    "Priority-only evening selection.",
                    "This baseline ranks POIs by priority and ignores area rotation, restaurant distance and route feasibility."
            ));
        }

        return new PlanDraftResponse(
                catalog.city(),
                catalog.country(),
                days,
                catalog.currency(),
                req.party(),
                normalizePace(req.pace()),
                catalog.city() + " " + days + "-Day Priority-Only Baseline",
                "A baseline itinerary generated by priority ranking only.",
                dayPlans,
                "priority-only"
        );
    }

    private List<PlanDraftResponse.Place> schedulePriorityOnly(
            List<LocalPoiItem> attractions,
            LocalPoiItem lunch,
            LocalPoiItem dinner
    ) {
        List<PlanDraftResponse.Place> stops = new ArrayList<>();
        int current = 9 * 60;
        for (int i = 0; i < attractions.size(); i++) {
            if (i == 1 && lunch != null) {
                current = Math.max(current + 20, 12 * 60 + 15);
                stops.add(scheduledPlace(lunch, "restaurant", "lunch", "lunch", current, 60));
                current += 80;
            }
            LocalPoiItem attraction = attractions.get(i);
            int stay = attraction.stayMinutes() == null ? 90 : Math.max(45, attraction.stayMinutes());
            String slot = current < 12 * 60 ? "morning" : "afternoon";
            stops.add(scheduledPlace(attraction, "attraction", slot, null, current, stay));
            current += stay + 20;
        }
        if (lunch != null && stops.stream().noneMatch(stop -> "lunch".equalsIgnoreCase(stop.mealType()))) {
            current = Math.max(current + 20, 12 * 60 + 15);
            stops.add(scheduledPlace(lunch, "restaurant", "lunch", "lunch", current, 60));
            current += 80;
        }
        if (dinner != null) {
            current = Math.max(current + 20, 17 * 60 + 30);
            stops.add(scheduledPlace(dinner, "restaurant", "dinner", "dinner", current, 75));
        }
        return stops;
    }

    private List<LocalPoiItem> restaurantsFor(LocalPoiCatalog catalog, String mealType) {
        return catalog.restaurants().stream()
                .filter(item -> item.hasMealType(mealType))
                .sorted(Comparator.comparingInt(this::priority).reversed().thenComparing(LocalPoiItem::name))
                .toList();
    }

    private String toCsvRow(String mode, CreatePlanReq req, LocalPlanQualityReport report) {
        Map<String, Long> counts = report.warnings().stream()
                .collect(Collectors.groupingBy(LocalPlanQualityReport.Warning::code, LinkedHashMap::new, Collectors.counting()));
        long lateMeals = countCodes(counts, Set.of("lunch-window", "dinner-window", "lunch-time-missing", "dinner-time-missing"));
        long duplicatePois = countCodes(counts, Set.of("duplicate-non-meal"));
        long farRestaurants = countCodes(counts, Set.of("meal-too-far"));
        long longTransfers = countCodes(counts, Set.of("long-transfer", "very-long-transfer"));
        long timeOverlaps = countCodes(counts, Set.of("time-overlap", "time-invalid"));
        long density = countCodes(counts, Set.of("day-density"));
        long themeArea = countCodes(counts, Set.of("theme-area-mixed", "theme-area-missing"));

        return String.join(",",
                csv(mode),
                csv(req.city()),
                String.valueOf(req.days()),
                csv(req.pace()),
                String.valueOf(req.party() == null || req.party().kids() == null ? 0 : req.party().kids()),
                String.valueOf(report.score()),
                String.valueOf(report.errorCount()),
                String.valueOf(report.warningCount()),
                String.valueOf(report.errorCount() + report.warningCount()),
                String.valueOf(lateMeals),
                String.valueOf(duplicatePois),
                String.valueOf(farRestaurants),
                String.valueOf(longTransfers),
                String.valueOf(timeOverlaps),
                String.valueOf(density),
                String.valueOf(themeArea)
        );
    }

    private long countCodes(Map<String, Long> counts, Set<String> codes) {
        return codes.stream().mapToLong(code -> counts.getOrDefault(code, 0L)).sum();
    }

    private PlanDraftResponse.Place scheduledPlace(
            LocalPoiItem item,
            String fallbackCategory,
            String timeSlot,
            String mealType,
            int startMinutes,
            int durationMinutes
    ) {
        return toPlace(
                item,
                fallbackCategory,
                timeSlot,
                mealType,
                formatMinutes(startMinutes),
                formatMinutes(startMinutes + durationMinutes),
                durationMinutes
        );
    }

    private PlanDraftResponse.Place toPlace(LocalPoiItem item, String fallbackCategory, String timeSlot, String mealType, String startTime) {
        return toPlace(item, fallbackCategory, timeSlot, mealType, startTime, null, null);
    }

    private PlanDraftResponse.Place toPlace(
            LocalPoiItem item,
            String fallbackCategory,
            String timeSlot,
            String mealType,
            String startTime,
            String endTime,
            Integer stayMinutes
    ) {
        if (item == null) {
            return null;
        }
        return new PlanDraftResponse.Place(
                item.name(),
                item.addressLine(),
                item.area(),
                item.city(),
                null,
                null,
                "Australia",
                item.category() == null || item.category().isBlank() ? fallbackCategory : item.category(),
                stayMinutes == null ? item.stayMinutes() : stayMinutes,
                timeSlot,
                startTime,
                endTime,
                mealType,
                item.area(),
                item.cuisine(),
                null,
                item.budgetLevel(),
                "Priority-only baseline selection.",
                "Baseline ignores distance-aware scheduling.",
                null,
                null,
                null,
                null,
                item.latitude(),
                item.longitude()
        );
    }

    private int nonMealStopsPerDay(String pace, CreatePlanReq.Party party) {
        int base = switch (normalizePace(pace)) {
            case "relaxed" -> 2;
            case "rush" -> 4;
            default -> 3;
        };
        int kids = party == null || party.kids() == null ? 0 : party.kids();
        return kids > 0 ? Math.max(2, base - 1) : base;
    }

    private String normalizePace(String pace) {
        if (pace == null) {
            return "normal";
        }
        String normalized = pace.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "relaxed", "rush" -> normalized;
            default -> "normal";
        };
    }

    private int priority(LocalPoiItem item) {
        return item == null || item.priority() == null ? 50 : item.priority();
    }

    private String formatMinutes(int minutes) {
        return LocalTime.of((minutes / 60) % 24, minutes % 60).format(TIME);
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
