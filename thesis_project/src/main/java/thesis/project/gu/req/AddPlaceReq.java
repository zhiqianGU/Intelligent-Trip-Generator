package thesis.project.gu.req;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import thesis.project.gu.dto.PlaceDto;

import java.math.BigDecimal;

public record AddPlaceReq(
        /** 高德返回的 poi_id */
        @NotBlank
        String poiId,

        /** 地点名称 */
        @NotBlank
        @Size(max = 128)
        String name,

        /** 纬度 */
        @NotNull
        BigDecimal latitude,

        /** 经度 */
        @NotNull
        BigDecimal longitude,

        /** 地址描述 */
        @NotBlank
        @Size(max = 255)
        String address,

        /** 备注，可选 */
        String note
) {
    /**
     * 转成 Service 层使用的 PlaceDto
     */
    public PlaceDto toDto() {
        return new PlaceDto(
                poiId,
                name,
                latitude,
                longitude,
                address,
                note
        );
    }
}
