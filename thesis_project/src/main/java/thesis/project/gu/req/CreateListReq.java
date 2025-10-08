package thesis.project.gu.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateListReq(
        @NotBlank
        @Size(max = 64)
        String listname,

        @Size(max = 225)
        String note
) {

}
