package thesis.project.gu.planning.domain;

import java.util.List;

public record SpecificationValidationResult(
        TripPlanningSpecification specification,
        boolean valid,
        boolean inputValid,
        boolean repaired,
        List<Issue> issues
) {
    public SpecificationValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public record Issue(
            String code,
            String message
    ) {
        public Issue {
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }
    }
}
