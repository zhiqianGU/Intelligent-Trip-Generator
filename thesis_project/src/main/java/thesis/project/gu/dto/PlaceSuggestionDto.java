package thesis.project.gu.dto;

import java.math.BigDecimal;

public record PlaceSuggestionDto(
        String poiId,       // 这里我们用 location 作为唯一标识
        String displayName, // 例如“阜通东大街6号（门址）”
        BigDecimal latitude,
        BigDecimal longitude,
        String address      // 完整地址：用户输入的那个
) {}
