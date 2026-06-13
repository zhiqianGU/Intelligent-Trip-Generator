package thesis.project.gu.user.api.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginReq(
        @NotBlank String login,
        @NotBlank String password,
        Boolean rememberMe,
        String challengeId,
        String challengeAnswer
) {}
