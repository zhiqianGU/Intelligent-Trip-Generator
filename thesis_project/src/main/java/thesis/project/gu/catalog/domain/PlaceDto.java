package thesis.project.gu.catalog.domain;

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
