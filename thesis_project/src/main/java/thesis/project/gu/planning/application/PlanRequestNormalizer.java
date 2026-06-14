package thesis.project.gu.planning.application;

import org.springframework.stereotype.Component;
import thesis.project.gu.planning.api.dto.CreatePlanReq;

import java.util.List;
import java.util.Locale;

@Component
public class PlanRequestNormalizer {

    public CreatePlanReq normalize(CreatePlanReq req) {
        if (req == null) {
            return null;
        }
        return new CreatePlanReq(
                normalizeText(req.city()),
                normalizeDays(req.days()),
                req.budget(),
                normalizeParty(req.party()),
                normalizeStyles(req.style()),
                normalizePace(req.pace()),
                normalizeMainModel(req.mainModel()),
                normalizeText(req.departureDate())
        );
    }

    public String normalizeMainModel(String mainModel) {
        return normalizeLowerText(mainModel);
    }

    public String normalizePace(String pace) {
        String normalized = normalizeLowerText(pace);
        if (normalized == null) {
            return "normal";
        }
        normalized = normalized.replace("_", "-").replace(" ", "-");
        return switch (normalized) {
            case "relaxed", "relax", "slow" -> "relaxed";
            case "rush", "fast", "fast-pace", "fastpaced", "intense" -> "rush";
            default -> "normal";
        };
    }

    public int normalizeDays(int days) {
        return Math.max(1, days);
    }

    public int normalizeKids(CreatePlanReq.Party party) {
        if (party == null || party.kids() == null) {
            return 0;
        }
        return Math.max(0, party.kids());
    }

    private CreatePlanReq.Party normalizeParty(CreatePlanReq.Party party) {
        if (party == null) {
            return new CreatePlanReq.Party(null, 0);
        }
        Integer adults = party.adults() == null ? null : Math.max(0, party.adults());
        return new CreatePlanReq.Party(adults, normalizeKids(party));
    }

    private List<String> normalizeStyles(List<String> styles) {
        if (styles == null) {
            return List.of();
        }
        return styles.stream()
                .map(this::normalizeLowerText)
                .filter(style -> style != null && !style.isBlank())
                .distinct()
                .toList();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeLowerText(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
