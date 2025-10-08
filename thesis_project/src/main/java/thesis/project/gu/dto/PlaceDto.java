package thesis.project.gu.dto;

import java.math.BigDecimal;

public record PlaceDto(
        String poiId,
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        String address,
        String note
) {

}
