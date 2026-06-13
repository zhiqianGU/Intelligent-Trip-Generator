package thesis.project.gu.catalog.heuristic;

import org.springframework.stereotype.Service;
import thesis.project.gu.routing.domain.StopCoordinate;
import thesis.project.gu.routing.infrastructure.dto.GeoResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class PlaceHeuristicService {
    public String normalizeSearchText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    public int commonSignificantTokenCount(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String token : left.split("\\s+")) {
            if (token.length() < 4 || isLowSignalPoiToken(token)) {
                continue;
            }
            if (right.contains(token)) {
                count++;
            }
        }
        return count;
    }

    public boolean isLowSignalPoiToken(String token) {
        return "the".equals(token)
                || "and".equals(token)
                || "australia".equals(token)
                || "sydney".equals(token)
                || "melbourne".equals(token)
                || "brisbane".equals(token)
                || "victoria".equals(token)
                || "south".equals(token)
                || "north".equals(token);
    }

    public String corePoiName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim();
        String[] parts = cleaned.split("\\s+(?:-|\\||:)\\s*");
        return parts.length == 0 ? cleaned : parts[0].trim();
    }

    public boolean isNavigationAnchorCandidate(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String text = name.toLowerCase(Locale.ROOT);
        return text.contains("harbour")
                || text.contains("parklands")
                || text.contains("botanic")
                || text.contains("garden")
                || text.contains("lookout")
                || text.contains("wharf")
                || text.contains("wharves")
                || text.contains("quay")
                || text.contains("pier")
                || text.contains("landmark")
                || text.contains("summit")
                || text.contains("mount ")
                || text.contains("mt ")
                || text.contains("beach")
                || text.contains("reserve")
                || text.contains("riverwalk")
                || text.contains("waterfront")
                || text.contains("precinct")
                || text.contains("trail")
                || text.contains("national park");
    }

    public boolean isParkStopForCoordinateRefresh(PlanDraftResponse.Place stop) {
        if (stop == null || stop.name() == null || stop.name().isBlank()) {
            return false;
        }
        String name = stop.name().toLowerCase(Locale.ROOT);
        if (!name.matches(".*\\bpark\\b.*")) {
            return false;
        }
        if (name.matches(".*\\b(car park|parking|parkroyal|park hotel|hotel|restaurant|cafe|bar)\\b.*")) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "park".equals(category)
                || "nature".equals(category)
                || "attraction".equals(category)
                || "outdoor".equals(category)
                || name.contains("national park")
                || name.contains("reserve")
                || name.contains("garden")
                || name.contains("lookout")
                || name.contains("beach");
    }

    public List<String> geocodeCandidates(
            PlanDraftResponse.Place stop,
            boolean strongPoiCandidate,
            boolean navigationAnchorCandidate
    ) {
        List<String> candidates = new ArrayList<>();
        if (stop == null) {
            return candidates;
        }
        String rawAddress = stop.addressLine() == null ? "" : stop.addressLine().trim();
        String name = stop.name() == null ? "" : stop.name().trim();
        if (strongPoiCandidate && !name.isBlank()) {
            if (!rawAddress.isBlank() && !rawAddress.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                addUnique(candidates, corePoiName(name) + ", " + rawAddress);
                addUnique(candidates, name + ", " + rawAddress);
            }
            if (stop.city() != null && !stop.city().isBlank()) {
                addUnique(candidates, corePoiName(name) + ", " + stop.city().trim());
                addUnique(candidates, name + ", " + stop.city().trim());
            }
            addUnique(candidates, corePoiName(name));
            addUnique(candidates, name);
        } else if (navigationAnchorCandidate && !name.isBlank()) {
            addUnique(candidates, name);
            if (stop.city() != null && !stop.city().isBlank()) {
                addUnique(candidates, name + ", " + stop.city().trim());
            }
        }
        if (!rawAddress.isBlank()) {
            addUnique(candidates, rawAddress);
            if (!name.isBlank() && !rawAddress.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                addUnique(candidates, name + ", " + rawAddress);
            }
        }
        if (!navigationAnchorCandidate && !strongPoiCandidate && !name.isBlank()) {
            addUnique(candidates, name);
            if (stop.city() != null && !stop.city().isBlank()) {
                addUnique(candidates, name + ", " + stop.city().trim());
            }
        }
        return candidates;
    }

    public StopCoordinate coordinateFromGeocode(GeoResponse response) {
        if (response == null || response.features() == null || response.features().isEmpty()) {
            return null;
        }
        GeoResponse.Feature feature = response.features().getFirst();
        if (feature == null || feature.properties() == null) {
            return null;
        }
        Double lat = feature.properties().lat();
        Double lon = feature.properties().lon();
        if ((lat == null || lon == null)
                && feature.geometry() != null
                && feature.geometry().coordinates() != null
                && feature.geometry().coordinates().size() >= 2) {
            lon = feature.geometry().coordinates().get(0);
            lat = feature.geometry().coordinates().get(1);
        }
        if (lat == null || lon == null) {
            return null;
        }
        return new StopCoordinate(lat, lon);
    }

    public boolean isCoordinatePlausibleForCity(StopCoordinate coordinate, String city) {
        if (coordinate == null || city == null || city.isBlank()) {
            return true;
        }
        String normalizedCity = city.trim().toLowerCase(Locale.ROOT);
        double lat = coordinate.lat();
        double lon = coordinate.lon();
        if ("brisbane".equals(normalizedCity)) {
            return lat >= -28.2 && lat <= -26.8 && lon >= 152.4 && lon <= 153.7;
        }
        if ("sydney".equals(normalizedCity)) {
            return lat >= -34.4 && lat <= -33.2 && lon >= 150.5 && lon <= 151.6;
        }
        if ("melbourne".equals(normalizedCity)) {
            return lat >= -38.5 && lat <= -37.2 && lon >= 144.2 && lon <= 145.6;
        }
        return true;
    }

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        boolean exists = values.stream().anyMatch(existing -> existing.equalsIgnoreCase(normalized));
        if (!exists) {
            values.add(normalized);
        }
    }

    private String normalizeSlot(String slot) {
        return slot == null ? "" : slot.trim().toLowerCase(Locale.ROOT);
    }
}
