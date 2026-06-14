package thesis.project.gu.routing.domain;

import java.util.List;

public record RouteDaySuggestion(int dayIndex, List<RouteSegmentSuggestion> segments) {}
