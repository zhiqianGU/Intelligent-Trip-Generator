package thesis.project.gu.planning.domain;

import thesis.project.gu.planning.api.dto.CreatePlanReq;

import java.util.List;

public record TripPlanningSpecification(
        Destination destination,
        int days,
        Integer budget,
        Party party,
        List<String> styles,
        String pace,
        String mainModel,
        String departureDate,
        Constraints constraints,
        List<SpecialEvent> specialEvents,
        List<DayStrategy> dayStrategies
) {
    public TripPlanningSpecification(
            Destination destination,
            int days,
            Integer budget,
            Party party,
            List<String> styles,
            String pace,
            String mainModel,
            String departureDate
    ) {
        this(destination, days, budget, party, styles, pace, mainModel, departureDate, Constraints.defaults(), List.of(), List.of());
    }

    public static TripPlanningSpecification fromRequest(CreatePlanReq req) {
        if (req == null) {
            return new TripPlanningSpecification(
                    new Destination(null),
                    1,
                    null,
                    new Party(2, 0),
                    List.of(),
                    "normal",
                    null,
                    null,
                    Constraints.defaults(),
                    List.of(),
                    List.of()
            );
        }
        CreatePlanReq.Party requestParty = req.party();
        return new TripPlanningSpecification(
                new Destination(req.city()),
                Math.max(1, req.days()),
                req.budget(),
                new Party(
                        requestParty == null ? null : requestParty.adults(),
                        requestParty == null ? null : requestParty.kids()
                ),
                req.style() == null ? List.of() : List.copyOf(req.style()),
                req.pace(),
                req.mainModel(),
                req.departureDate(),
                Constraints.fromRequest(req),
                List.of(),
                List.of()
        );
    }

    public CreatePlanReq toRequest() {
        return new CreatePlanReq(
                destination == null ? null : destination.city(),
                days,
                budget,
                party == null ? null : new CreatePlanReq.Party(party.adults(), party.kids()),
                styles,
                pace,
                mainModel,
                departureDate
        );
    }

    public TripPlanningSpecification withResolvedDestination(thesis.project.gu.catalog.domain.Destination resolvedDestination) {
        if (resolvedDestination == null) {
            return this;
        }
        return new TripPlanningSpecification(
                Destination.fromCatalogDestination(resolvedDestination),
                days,
                budget,
                party,
                styles,
                pace,
                mainModel,
                departureDate,
                constraints,
                specialEvents,
                dayStrategies
        );
    }

    public TripPlanningSpecification withAgentOutput(PlanningAgentOutput output) {
        if (output == null) {
            return this;
        }
        return new TripPlanningSpecification(
                destination,
                days,
                budget,
                party,
                styles,
                pace,
                mainModel,
                departureDate,
                output.normalizedConstraints() == null ? constraints : output.normalizedConstraints(),
                output.specialEvents(),
                output.dayStrategies()
        );
    }

    public record Destination(
            String destinationId,
            String city,
            String state,
            String country,
            String timezone,
            boolean resolved
    ) {
        public Destination(String city) {
            this(null, city, null, null, null, false);
        }

        public static Destination fromCatalogDestination(thesis.project.gu.catalog.domain.Destination destination) {
            if (destination == null) {
                return new Destination(null);
            }
            return new Destination(
                    destination.destinationId(),
                    destination.city(),
                    destination.state(),
                    destination.country(),
                    destination.timezone(),
                    destination.resolved()
            );
        }
    }

    public record Party(Integer adults, Integer kids) {
    }

    public record Constraints(
            String preferredStartTime,
            String pace,
            Integer budget,
            List<String> preferredStyles,
            boolean familyFriendly
    ) {
        public static Constraints defaults() {
            return new Constraints("09:00", "normal", null, List.of(), false);
        }

        public static Constraints fromRequest(CreatePlanReq req) {
            String pace = req == null || req.pace() == null || req.pace().isBlank() ? "normal" : req.pace();
            String preferredStartTime = "relaxed".equalsIgnoreCase(pace) ? "10:00" : "09:00";
            CreatePlanReq.Party party = req == null ? null : req.party();
            int kids = party == null || party.kids() == null ? 0 : party.kids();
            return new Constraints(
                    preferredStartTime,
                    pace,
                    req == null ? null : req.budget(),
                    req == null || req.style() == null ? List.of() : List.copyOf(req.style()),
                    kids > 0
            );
        }
    }

    public record SpecialEvent(
            int day,
            String type,
            List<String> requiredCapabilities
    ) {
    }

    public record DayStrategy(
            int day,
            String theme,
            String primaryZoneId,
            List<String> fallbackZoneIds,
            String allocation,
            List<String> preferredPoiTypes,
            List<String> requiredCapabilities
    ) {
    }
}
