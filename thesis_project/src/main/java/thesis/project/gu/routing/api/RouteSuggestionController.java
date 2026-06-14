package thesis.project.gu.routing.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.routing.application.RouteSuggestionService;
import thesis.project.gu.routing.domain.RouteDaySuggestion;

@RestController
@RequestMapping("/api/v1/plans")
public class RouteSuggestionController {
    private final RouteSuggestionService routeSuggestionService;

    public RouteSuggestionController(RouteSuggestionService routeSuggestionService) {
        this.routeSuggestionService = routeSuggestionService;
    }

    @PostMapping(value = "/route-suggestions/day", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RouteDaySuggestion routeSuggestionDay(@RequestBody RouteSuggestionDayRequest request) {
        return routeSuggestionService.buildRouteSuggestionDay(
                request == null ? null : request.draft(),
                request == null ? null : request.dayIndex(),
                request == null ? null : request.departureDate()
        );
    }

    public record RouteSuggestionDayRequest(PlanDraftResponse draft, Integer dayIndex, Integer budget, String departureDate) {}
}
