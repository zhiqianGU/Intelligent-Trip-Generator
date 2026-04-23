package thesis.project.gu.response;
public record LoginResult(
        String accessToken,
        String refreshToken,
        Long userId,
        String username,
        boolean rememberMe
) {}
