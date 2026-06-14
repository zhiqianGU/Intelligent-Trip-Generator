package thesis.project.gu.routing.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.routing.domain.ModeSummary;
import thesis.project.gu.routing.domain.RouteChoice;
import thesis.project.gu.routing.domain.RouteRecommendationContext;

@Service
public class RouteModeRecommendationService {
    private final MapService mapService;

    public RouteModeRecommendationService(MapService mapService) {
        this.mapService = mapService;
    }

    public RouteChoice resolveRouteChoice(
            String origin,
            String destination,
            RouteRecommendationContext context,
            boolean rainy
    ) {
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            return new RouteChoice(null, null, null, null);
        }
        ModeSummary walk = modeSummary("walk", () -> mapService.walk_summary(origin, destination));
        ModeSummary transit = modeSummary("transit", () -> mapService.transit_summary(origin, destination));
        ModeSummary car = normalizeCarSummary(modeSummary("car", () -> mapService.car_summary(origin, destination)));
        ModeSummary recommended = recommendMode(walk, transit, car, context, rainy);
        return new RouteChoice(walk, transit, car, recommended);
    }

    private ModeSummary modeSummary(String mode, RouteSummarySupplier supplier) {
        try {
            MapService.RouteSummary summary = supplier.get();
            if (summary == null || summary.durationSeconds() == null || summary.durationSeconds().isBlank()) {
                return null;
            }
            Integer durationMinutes = routeSummaryMinutes(() -> summary);
            Integer distanceMeters = parseInteger(summary.distanceMeters());
            if (durationMinutes == null || durationMinutes <= 0) {
                return null;
            }
            return new ModeSummary(mode, durationMinutes, distanceMeters);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ModeSummary normalizeCarSummary(ModeSummary car) {
        if (car == null || car.distanceMeters() == null || car.distanceMeters() <= 0) {
            return car;
        }
        int urbanFloor = Math.max(5, (int) Math.ceil((car.distanceMeters() / 1000.0) / 25.0 * 60.0) + 3);
        int normalizedMinutes = Math.max(car.durationMinutes(), urbanFloor);
        return new ModeSummary(car.mode(), normalizedMinutes, car.distanceMeters());
    }

    private ModeSummary recommendMode(
            ModeSummary walk,
            ModeSummary transit,
            ModeSummary car,
            RouteRecommendationContext context,
            boolean rainy
    ) {
        boolean hasKids = context != null && context.hasKids();
        int walkDirectThreshold = hasKids ? 15 : 20;
        int walkCompareThreshold = hasKids ? 20 : 30;
        if (rainy) {
            walkDirectThreshold = Math.min(walkDirectThreshold, 10);
            walkCompareThreshold = Math.min(walkCompareThreshold, 15);
        }

        if (walk != null && walk.durationMinutes() <= walkDirectThreshold) {
            return walk;
        }

        if (transit != null) {
            if (walk != null
                    && walk.durationMinutes() <= walkCompareThreshold
                    && transit.durationMinutes() - walk.durationMinutes() > -8) {
                return walk;
            }
            if (car != null && transit.durationMinutes() - car.durationMinutes() >= 20) {
                return car;
            }
            return transit;
        }

        if (car != null) {
            return car;
        }

        return walk;
    }

    private Integer routeSummaryMinutes(RouteSummarySupplier supplier) {
        try {
            MapService.RouteSummary summary = supplier.get();
            if (summary == null || summary.durationSeconds() == null || summary.durationSeconds().isBlank()) {
                return null;
            }
            int seconds = (int) Math.round(Double.parseDouble(summary.durationSeconds().trim()));
            if (seconds <= 0) {
                return null;
            }
            return Math.max(1, (int) Math.ceil(seconds / 60.0));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private interface RouteSummarySupplier {
        MapService.RouteSummary get();
    }
}
