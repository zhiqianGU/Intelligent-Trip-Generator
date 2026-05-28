package thesis.project.gu.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordReq(
        @NotBlank
        String login,

        @NotBlank
        @Size(min = 6, max = 32)
        String password
) {}
