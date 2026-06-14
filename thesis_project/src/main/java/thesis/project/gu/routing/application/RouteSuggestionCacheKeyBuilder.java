package thesis.project.gu.routing.application;

import org.springframework.stereotype.Component;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;

@Component
public class RouteSuggestionCacheKeyBuilder {
    public String buildDayKey(PlanDraftResponse draft, PlanDraftResponse.DayPlan day, String departureDate) {
        if (draft == null || day == null) {
            return "";
        }
        StringBuilder source = new StringBuilder(512)
                .append("city=").append(nullToEmpty(draft.city()))
                .append("|days=").append(draft.days())
                .append("|pace=").append(nullToEmpty(draft.pace()))
                .append("|kids=").append(draft.party() == null ? "" : draft.party().kids())
                .append("|departure=").append(nullToEmpty(departureDate))
                .append("|copy=").append(nullToEmpty(draft.copyPolishStatus()))
                .append("|day=").append(day.dayIndex());
        appendPlace(source, "hotel", day.hotel());
        if (day.stops() != null) {
            for (int i = 0; i < day.stops().size(); i++) {
                appendPlace(source, "stop-" + i, day.stops().get(i));
            }
        }
        return "route-suggestion-day:" + sha256Url(source.toString());
    }

    private void appendPlace(StringBuilder source, String label, PlanDraftResponse.Place place) {
        source.append('|').append(label).append('=');
        if (place == null) {
            source.append("null");
            return;
        }
        source.append(nullToEmpty(place.name()))
                .append('@').append(nullToEmpty(place.addressLine()))
                .append('@').append(nullToEmpty(place.startTime()))
                .append('-').append(nullToEmpty(place.endTime()))
                .append('@').append(place.latitude() == null ? "" : roundCoordinate(place.latitude()))
                .append(',')
                .append(place.longitude() == null ? "" : roundCoordinate(place.longitude()));
    }

    private String sha256Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String roundCoordinate(Double value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.5f", value);
    }
}
