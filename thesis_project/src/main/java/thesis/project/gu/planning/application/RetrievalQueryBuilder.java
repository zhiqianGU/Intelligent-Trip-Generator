package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.domain.Destination;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;
import thesis.project.gu.planning.domain.TripPlanningSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RetrievalQueryBuilder {
    public PlanningZoneRetrievalQuery build(
            ParsedPlanningRequest parsedRequest,
            Destination destination,
            TripPlanningSpecification specification
    ) {
        ParsedPlanningRequest safeParsed = parsedRequest == null
                ? defaultParsedRequest()
                : parsedRequest;
        TripPlanningSpecification.Destination specDestination = specification == null ? null : specification.destination();
        String destinationId = firstNonBlank(
                destination == null ? null : destination.destinationId(),
                specDestination == null ? null : specDestination.destinationId()
        );
        String destinationCity = firstNonBlank(
                destination == null ? null : destination.city(),
                specDestination == null ? null : specDestination.city(),
                safeParsed.destinationCandidate()
        );
        List<String> preferredStyles = preferredStyles(safeParsed, specification);
        Map<String, Object> detectedHints = detectedHints(safeParsed);
        return new PlanningZoneRetrievalQuery(
                destinationId,
                destinationCity,
                true,
                minimumAllocation(safeParsed),
                semanticQuery(safeParsed, preferredStyles),
                detectedHints,
                preferredStyles,
                safeParsed.pace(),
                safeParsed.travellers()
        );
    }

    private Map<String, Object> detectedHints(ParsedPlanningRequest parsedRequest) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("lateStartPreferred", parsedRequest.lateStartPreferred());
        hints.put("familyFriendly", parsedRequest.familyFriendly());
        hints.put("preferIndoorWhenRaining", parsedRequest.preferIndoorWhenRaining());
        if (parsedRequest.budget() != null) {
            hints.put("budget", parsedRequest.budget());
        }
        if (parsedRequest.specialDayHints() != null && !parsedRequest.specialDayHints().isEmpty()) {
            hints.put("specialDayHints", parsedRequest.specialDayHints());
        }
        return Map.copyOf(hints);
    }

    private List<String> preferredStyles(ParsedPlanningRequest parsedRequest, TripPlanningSpecification specification) {
        List<String> values = new ArrayList<>();
        if (parsedRequest.preferenceHints() != null) {
            values.addAll(parsedRequest.preferenceHints());
        }
        if (specification != null && specification.styles() != null) {
            for (String style : specification.styles()) {
                if (style != null && !style.isBlank() && !values.contains(style)) {
                    values.add(style);
                }
            }
        }
        return List.copyOf(values);
    }

    private String semanticQuery(ParsedPlanningRequest parsedRequest, List<String> preferredStyles) {
        List<String> parts = new ArrayList<>();
        if (parsedRequest.rawText() != null && !parsedRequest.rawText().isBlank()) {
            parts.add(parsedRequest.rawText().trim());
        }
        if (preferredStyles != null && !preferredStyles.isEmpty()) {
            parts.add(String.join(" ", preferredStyles));
        }
        if (parsedRequest.pace() != null && !parsedRequest.pace().isBlank()) {
            parts.add(parsedRequest.pace());
        }
        if (parsedRequest.familyFriendly()) {
            parts.add("family friendly");
        }
        if (parsedRequest.lateStartPreferred()) {
            parts.add("late start");
        }
        if (parsedRequest.preferIndoorWhenRaining()) {
            parts.add("indoor rainy day");
        }
        return String.join(" ", parts).trim();
    }

    private String minimumAllocation(ParsedPlanningRequest parsedRequest) {
        return parsedRequest != null && "relaxed".equals(parsedRequest.pace()) ? "HALF_DAY" : "FULL_OR_HALF_DAY";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private ParsedPlanningRequest defaultParsedRequest() {
        return new ParsedPlanningRequest(
                null,
                1,
                2,
                "normal",
                null,
                List.of(),
                List.of(),
                false,
                false,
                false,
                null
        );
    }
}
