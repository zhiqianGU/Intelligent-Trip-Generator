package thesis.project.gu.routing.prewarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import thesis.project.gu.planhistory.persistence.TripPlanMapper;
import thesis.project.gu.planhistory.domain.TripDayView;
import thesis.project.gu.planhistory.domain.TripStopView;
import thesis.project.gu.observability.application.RuntimeMetricsService;
import thesis.project.gu.routing.application.MapService;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;
import thesis.project.gu.planhistory.domain.PlaceDetail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
public class PlanPrewarmService {
    private static final Logger log = LoggerFactory.getLogger(PlanPrewarmService.class);

    private final TripPlanMapper tripPlanMapper;
    private final MapService mapService;
    private final ExecutorService planPrewarmExecutor;
    private final RuntimeMetricsService runtimeMetricsService;

    public PlanPrewarmService(
            TripPlanMapper tripPlanMapper,
            MapService mapService,
            @Qualifier("planPrewarmExecutor")
            ExecutorService planPrewarmExecutor,
            RuntimeMetricsService runtimeMetricsService
    ) {
        this.tripPlanMapper = tripPlanMapper;
        this.mapService = mapService;
        this.planPrewarmExecutor = planPrewarmExecutor;
        this.runtimeMetricsService = runtimeMetricsService;
    }

    public void prewarmPlanAsync(long planId, String fallbackCity) {
        CompletableFuture.runAsync(() -> prewarmPlan(planId, fallbackCity), planPrewarmExecutor)
                .exceptionally(ex -> {
                    log.warn("Plan prewarm task failed for planId={}", planId, ex);
                    return null;
                });
    }

    private void prewarmPlan(long planId, String fallbackCity) {
        long startedAt = System.currentTimeMillis();
        long geocodeCalls = 0L;
        long routeCalls = 0L;

        try {
            List<TripDayView> days = tripPlanMapper.findDaysByPlan(planId);
            if (days == null || days.isEmpty()) {
                runtimeMetricsService.recordPlanPrewarm(System.currentTimeMillis() - startedAt, true, 0, 0);
                return;
            }

            List<Long> dayIds = days.stream().map(TripDayView::getId).filter(Objects::nonNull).toList();
            List<TripStopView> stops = dayIds.isEmpty() ? List.of() : tripPlanMapper.findStopsByDayIds(dayIds);

            Set<Long> placeIds = collectPlaceIds(days, stops);
            Map<Long, PlaceDetail> placeMap = placeIds.isEmpty()
                    ? Map.of()
                    : tripPlanMapper.findPlacesByIds(new ArrayList<>(placeIds)).stream()
                    .collect(Collectors.toMap(PlaceDetail::id, p -> p, (a, b) -> a, LinkedHashMap::new));

            Map<Long, List<TripStopView>> stopsByDay = stops.stream()
                    .collect(Collectors.groupingBy(TripStopView::getDayId));

            for (TripDayView day : days.stream().sorted(Comparator.comparing(TripDayView::getDayIndex)).toList()) {
                List<PlanPoint> points = new ArrayList<>();

                if (day.getHotelPlaceId() != null) {
                    PlaceDetail hotel = placeMap.get(day.getHotelPlaceId());
                    if (hotel != null) {
                        PlanPoint resolved = resolvePoint(hotel, fallbackCity);
                        if (resolved != null) {
                            points.add(resolved);
                            if (resolved.usedGeocode()) geocodeCalls++;
                        }
                    }
                }

                for (TripStopView stop : stopsByDay.getOrDefault(day.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(TripStopView::getSeq))
                        .toList()) {
                    if (stop.getPlaceId() == null) continue;
                    PlaceDetail place = placeMap.get(stop.getPlaceId());
                    if (place == null) continue;
                    PlanPoint resolved = resolvePoint(place, fallbackCity);
                    if (resolved != null) {
                        points.add(resolved);
                        if (resolved.usedGeocode()) geocodeCalls++;
                    }
                }

                for (int i = 0; i + 1 < points.size(); i++) {
                    String origin = points.get(i).lat() + "," + points.get(i).lng();
                    String destination = points.get(i + 1).lat() + "," + points.get(i + 1).lng();

                    try { mapService.transit_route(origin, destination); } catch (RuntimeException ignored) {}
                    try { mapService.walk_route(origin, destination); } catch (RuntimeException ignored) {}
                    try { mapService.car_route(origin, destination); } catch (RuntimeException ignored) {}
                    try { mapService.transit_summary(origin, destination); } catch (RuntimeException ignored) {}
                    try { mapService.walk_summary(origin, destination); } catch (RuntimeException ignored) {}
                    try { mapService.car_summary(origin, destination); } catch (RuntimeException ignored) {}
                    routeCalls += 6;
                }
            }

            runtimeMetricsService.recordPlanPrewarm(System.currentTimeMillis() - startedAt, true, geocodeCalls, routeCalls);
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanPrewarm(System.currentTimeMillis() - startedAt, false, geocodeCalls, routeCalls);
            throw e;
        }
    }

    private Set<Long> collectPlaceIds(List<TripDayView> days, List<TripStopView> stops) {
        Set<Long> placeIds = days.stream()
                .map(TripDayView::getHotelPlaceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        placeIds.addAll(stops.stream()
                .map(TripStopView::getPlaceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return placeIds;
    }

    private PlanPoint resolvePoint(PlaceDetail place, String fallbackCity) {
        if (place.latitude() != null && place.longitude() != null) {
            return new PlanPoint(place.latitude(), place.longitude(), false);
        }

        String query = sanitizeAddressQuery(place.addressLine());
        if (query == null) return null;

        GeoResponse response = mapService.geocodePlaceName(
                firstNonBlank(place.name(), query),
                firstNonBlank(place.city(), fallbackCity),
                place.country()
        );
        if (response == null || response.features() == null || response.features().isEmpty()) return null;
        GeoResponse.Properties props = response.features().get(0).properties();
        if (props == null || props.lat() == null || props.lon() == null) return null;
        return new PlanPoint(props.lat(), props.lon(), true);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private String sanitizeAddressQuery(String addressLine) {
        String address = firstNonBlank(addressLine);
        if (address == null) return null;

        String normalized = address.replaceAll("\\s+", " ").trim();
        if (normalized.length() < 12) return null;

        boolean hasComma = normalized.contains(",");
        boolean hasDigit = normalized.chars().anyMatch(Character::isDigit);
        if (!hasComma && !hasDigit) {
            return null;
        }
        return normalized;
    }

    private record PlanPoint(Double lat, Double lng, boolean usedGeocode) {
    }
}
