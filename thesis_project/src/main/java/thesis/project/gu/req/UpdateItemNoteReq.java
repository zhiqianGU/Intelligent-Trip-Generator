package thesis.project.gu.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateItemNoteReq(
        @NotNull(message = "note 不能为空") @Size(max = 255) String note
) {}
