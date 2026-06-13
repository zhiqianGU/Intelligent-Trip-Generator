package thesis.project.gu.routing.api.dto;

import java.math.BigDecimal;

public record PlaceSuggestionDto(
        String poiId,
        String name,
        BigDecimal lat,
        BigDecimal lng,
        String originalQuery
) {}
