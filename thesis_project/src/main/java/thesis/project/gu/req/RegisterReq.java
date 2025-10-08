package thesis.project.gu.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterReq(
        @NotBlank
        @Size(min = 6, max = 64)
        String login,

        @NotBlank
        @Size(min = 6, max = 32)
        String password,

        String name
) {}