package thesis.project.gu.dto;

import java.math.BigDecimal;

public record PlaceSuggestionDto(
        String poiId,
        String name,
        BigDecimal lat,
        BigDecimal lng,
        String originalQuery
) {}
