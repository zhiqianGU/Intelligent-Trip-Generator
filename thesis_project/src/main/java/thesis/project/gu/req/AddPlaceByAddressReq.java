package thesis.project.gu.req;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AddPlaceByAddressReq(
        @NotBlank(message = "地址不能为空")
        String address,

        @Min(value = 0, message = "selectedIndex 必须 ≥ 0")
        int selectedIndex,

        String note
) {}
