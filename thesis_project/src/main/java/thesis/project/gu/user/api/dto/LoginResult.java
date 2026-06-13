package thesis.project.gu.user.api.dto;
public record LoginResult(
        String accessToken,
        String refreshToken,
        Long userId,
        String username,
        boolean rememberMe
) {}
