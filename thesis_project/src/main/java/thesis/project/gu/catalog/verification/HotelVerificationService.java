package thesis.project.gu.catalog.verification;

import org.springframework.stereotype.Service;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HotelVerificationService {
    private static final Pattern AU_LOCALITY_PATTERN = Pattern.compile("^(.*?)(?:\\s+([A-Z]{2,3})\\s+(\\d{4}))?$");
    private final GooglePlacesClient googlePlacesClient;

    public HotelVerificationService(GooglePlacesClient googlePlacesClient) {
        this.googlePlacesClient = googlePlacesClient;
    }

    public VerificationResult verifyAndNormalize(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || !googlePlacesClient.isEnabled()) {
            return new VerificationResult(draft, List.of());
        }

        PlanDraftResponse.Place selectedHotel = null;
        List<String> issues = new ArrayList<>();
        List<PlanDraftResponse.DayPlan> normalizedDays = new ArrayList<>();

        for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
            if (selectedHotel == null && day.hotel() != null) {
                VerifiedHotel verified = verifyHotel(day.hotel(), draft.city());
                if (verified.issue != null) {
                    issues.add("day-" + day.dayIndex() + "-hotel-" + verified.issue);
                } else {
                    selectedHotel = verified.place;
                }
            }
        }

        for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
            normalizedDays.add(new PlanDraftResponse.DayPlan(
                    day.dayIndex(),
                    selectedHotel != null ? selectedHotel : day.hotel(),
                    day.stops(),
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }

        return new VerificationResult(new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                normalizedDays,
                draft.copyPolishStatus()
        ), issues);
    }

    private VerifiedHotel verifyHotel(PlanDraftResponse.Place hotel, String city) {
        List<String> queries = buildQueries(hotel, city);
        for (String query : queries) {
            List<GooglePlacesClient.PlaceCandidate> candidates = googlePlacesClient.searchText(query, city);
            if (candidates.isEmpty()) {
                continue;
            }
            GooglePlacesClient.PlaceCandidate best = candidates.stream()
                    .filter(this::looksLikeHotel)
                    .map(candidate -> new RankedCandidate(candidate, score(hotel, candidate)))
                    .sorted((a, b) -> Integer.compare(b.score, a.score))
                    .map(RankedCandidate::candidate)
                    .findFirst()
                    .orElse(null);
            if (best == null) {
                continue;
            }
            if (score(hotel, best) < 75) {
                return new VerifiedHotel(hotel, "google-places-low-confidence");
            }
            if (best.businessStatus() != null && best.businessStatus().toUpperCase(Locale.ROOT).contains("CLOSED")) {
                return new VerifiedHotel(hotel, "google-places-closed");
            }
            return new VerifiedHotel(normalizeHotel(hotel, best, city), null);
        }
        return new VerifiedHotel(hotel, "google-places-no-match");
    }

    private PlanDraftResponse.Place normalizeHotel(PlanDraftResponse.Place hotel, GooglePlacesClient.PlaceCandidate best, String city) {
        ParsedAddress parsed = parseAustralianAddress(best.formattedAddress(), city, hotel.country());
        String normalizedArea = !safe(parsed.suburb()).isBlank() ? parsed.suburb() : safe(parsed.city());
        return new PlanDraftResponse.Place(
                isBlank(best.name()) ? hotel.name() : best.name(),
                parsed.addressLine(),
                parsed.suburb(),
                parsed.city(),
                parsed.state(),
                parsed.postcode(),
                parsed.country(),
                "hotel",
                hotel.stayMinutes(),
                hotel.timeSlot(),
                hotel.startTime(),
                hotel.endTime(),
                hotel.mealType(),
                hotel.preferredArea(),
                hotel.cuisine(),
                hotel.vibe(),
                hotel.budgetLevel(),
                normalizeReason(best, normalizedArea, parsed.city()),
                normalizeTip(best, normalizedArea, parsed.city()),
                best.websiteUri(),
                best.googleMapsUri(),
                best.businessStatus(),
                !isBlank(best.websiteUri()) ? best.websiteUri() : (!isBlank(best.googleMapsUri()) ? best.googleMapsUri() : hotel.url()),
                Double.isNaN(best.lat()) ? hotel.latitude() : best.lat(),
                Double.isNaN(best.lng()) ? hotel.longitude() : best.lng()
        );
    }

    private List<String> buildQueries(PlanDraftResponse.Place hotel, String city) {
        List<String> queries = new ArrayList<>();
        String preferredArea = safe(hotel.preferredArea());
        String vibe = safe(hotel.vibe());
        String budget = safe(hotel.budgetLevel());
        String name = safe(hotel.name());
        String address = safe(hotel.addressLine());

        addQuery(queries, join(" ", name, city));
        if (!address.isBlank()) {
            addQuery(queries, join(" ", name, address));
            addQuery(queries, join(" ", "hotel", address));
        }
        addQuery(queries, join(" ", vibe, budget, "hotel", preferredArea.isBlank() ? city : preferredArea));
        addQuery(queries, join(" ", budget, "hotel", city));
        return queries;
    }

    private boolean looksLikeHotel(GooglePlacesClient.PlaceCandidate candidate) {
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        return types.contains("lodging")
                || types.contains("hotel")
                || types.contains("inn")
                || types.contains("accommodation")
                || types.contains("resort");
    }

    private int score(PlanDraftResponse.Place hotel, GooglePlacesClient.PlaceCandidate candidate) {
        int score = 0;
        String hotelName = normalize(hotel.name());
        String candidateName = normalize(candidate.name());
        String hotelAddress = normalize(hotel.addressLine());
        String candidateAddress = normalize(candidate.formattedAddress());
        if (!hotelName.isBlank() && candidateName.contains(hotelName)) score += 90;
        if (!candidateName.isBlank() && hotelName.contains(candidateName)) score += 70;
        score += commonTokenCount(hotelName, candidateName) * 18;
        score += commonTokenCount(hotelAddress, candidateAddress) * 8;
        if (looksLikeHotel(candidate)) score += 45;
        if (candidateName.contains("hotel")) score += 35;
        if (candidateName.contains("hostel") || candidateName.contains("motel") || candidateName.contains("apartment")) score -= 25;
        String status = candidate.businessStatus() == null ? "" : candidate.businessStatus().toUpperCase(Locale.ROOT);
        if ("OPERATIONAL".equals(status)) score += 25;
        if (status.contains("CLOSED")) score -= 120;
        return score;
    }

    private int commonTokenCount(String a, String b) {
        if (a.isBlank() || b.isBlank()) return 0;
        List<String> tokensA = List.of(a.split("\\s+"));
        List<String> tokensB = List.of(b.split("\\s+"));
        int count = 0;
        for (String token : tokensA) {
            if (token.length() > 2 && tokensB.contains(token)) count++;
        }
        return count;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private void addQuery(List<String> queries, String query) {
        String normalized = safe(query);
        if (!normalized.isBlank() && !queries.contains(normalized)) {
            queries.add(normalized);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    private String normalizeReason(GooglePlacesClient.PlaceCandidate candidate, String area, String city) {
        String hotelName = isBlank(candidate.name()) ? "This hotel" : candidate.name();
        return hotelName + " provides a practical base in " + areaLabel(area, city)
                + ", keeping the main sightseeing areas reachable without changing hotels.";
    }

    private String normalizeTip(GooglePlacesClient.PlaceCandidate candidate, String area, String city) {
        return "Confirm check-in details and book ahead if you want the smoothest stay in " + areaLabel(area, city) + ".";
    }

    private String areaLabel(String area, String city) {
        return !safe(area).isBlank() ? area : safe(city);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ParsedAddress parseAustralianAddress(String formattedAddress, String fallbackCity, String fallbackCountry) {
        if (isBlank(formattedAddress)) {
            return new ParsedAddress("", "", safe(fallbackCity), "", "", safe(fallbackCountry));
        }
        String[] parts = formattedAddress.split(",");
        String addressLine = parts.length > 0 ? safe(parts[0]) : safe(formattedAddress);
        String localityBlock = "";
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = safe(parts[i]);
            if (part.matches(".*\\b[A-Z]{2,3}\\b\\s+\\d{4}.*")) {
                localityBlock = part;
                break;
            }
        }
        String country = "Australia";
        String suburb = "";
        String state = "";
        String postcode = "";
        if (!localityBlock.isBlank()) {
            Matcher matcher = AU_LOCALITY_PATTERN.matcher(localityBlock);
            if (matcher.matches()) {
                suburb = safe(matcher.group(1));
                state = safe(matcher.group(2));
                postcode = safe(matcher.group(3));
            }
        }
        return new ParsedAddress(
                addressLine,
                suburb,
                safe(fallbackCity),
                state,
                postcode,
                country.isBlank() ? safe(fallbackCountry) : country
        );
    }

    public record VerificationResult(PlanDraftResponse draft, List<String> issues) {}

    private record VerifiedHotel(PlanDraftResponse.Place place, String issue) {}

    private record RankedCandidate(GooglePlacesClient.PlaceCandidate candidate, int score) {}

    private record ParsedAddress(String addressLine, String suburb, String city, String state, String postcode, String country) {}
}
