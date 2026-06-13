package thesis.project.gu.user.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.user.api.dto.LoginReq;
import thesis.project.gu.user.api.dto.RegisterReq;
import thesis.project.gu.user.api.dto.ResetPasswordReq;
import thesis.project.gu.user.application.AuthService;
import thesis.project.gu.user.security.CaptchaChallengeService;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final CaptchaChallengeService captchaChallengeService;
    private final String resetPasswordToken;

    public AuthController(
            AuthService authService,
            CaptchaChallengeService captchaChallengeService,
            @Value("${app.auth.reset-password-token:}") String resetPasswordToken
    ) {
        this.authService = authService;
        this.captchaChallengeService = captchaChallengeService;
        this.resetPasswordToken = resetPasswordToken;
    }

    record TokenResp(String token, Map<String, Object> user) {}
    record ChallengeResp(String challengeId, String question) {}

    @GetMapping("/challenge")
    public ChallengeResp challenge() {
        var challenge = captchaChallengeService.create();
        return new ChallengeResp(challenge.challengeId(), challenge.question());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResp register(@RequestBody @Validated RegisterReq req,
                              HttpServletRequest request,
                              HttpServletResponse response) {
        captchaChallengeService.verifyRequired(req.challengeId(), req.challengeAnswer());
        authService.register(req.login(), req.password(), req.name());
        authService.clearLoginAttempts(req.login(), request.getRemoteAddr());
        var result = authService.login(
                req.login(),
                req.password(),
                false,
                request.getHeader("User-Agent"),
                request.getRemoteAddr(),
                null,
                null
        );
        addRefreshCookie(response, result.refreshToken(), false);
        return new TokenResp(
                result.accessToken(),
                Map.of("userId", result.userId(), "username", result.username())
        );
    }

    @PostMapping("/login")
    public TokenResp login(@Valid @RequestBody LoginReq req,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        boolean rememberMe = Boolean.TRUE.equals(req.rememberMe());
        var result = authService.login(
                req.login(),
                req.password(),
                rememberMe,
                request.getHeader("User-Agent"),
                request.getRemoteAddr(),
                req.challengeId(),
                req.challengeAnswer()
        );

        addRefreshCookie(response, result.refreshToken(), rememberMe);

        return new TokenResp(
                result.accessToken(),
                Map.of("userId", result.userId(), "username", result.username())
        );
    }

    @PostMapping("/refresh")
    public TokenResp refresh(@CookieValue(name = "refresh_token", required = false) String refreshToken,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new NavigatorException(ErrorCode.UNAUTHORIZED, "Refresh token missing");
        }

        var result = authService.refresh(
                refreshToken,
                request.getHeader("User-Agent"),
                request.getRemoteAddr()
        );

        addRefreshCookie(response, result.refreshToken(), result.rememberMe());

        return new TokenResp(
                result.accessToken(),
                Map.of("userId", result.userId(), "username", result.username())
        );
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = "refresh_token", required = false) String refreshToken,
                       HttpServletResponse response) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        clearRefreshCookie(response);
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(
            @RequestBody @Valid ResetPasswordReq req,
            @RequestHeader(name = "X-Reset-Password-Token", required = false) String token
    ) {
        if (resetPasswordToken == null || resetPasswordToken.isBlank() || !resetPasswordToken.equals(token)) {
            throw ErrorCode.UNAUTHORIZED.ex("Invalid reset password token");
        }
        long userId = authService.resetPassword(req.login(), req.password());
        return Map.of("userId", userId, "status", "password-reset");
    }

    private void addRefreshCookie(HttpServletResponse response, String refreshToken, boolean rememberMe) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("refresh_token", refreshToken)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/");

        if (rememberMe) {
            builder.maxAge(Duration.ofDays(30));
        }

        response.addHeader("Set-Cookie", builder.build().toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal(errorOnInvalidType = false) Long userId) {
        if (userId == null) {
            throw new NavigatorException(ErrorCode.UNAUTHORIZED, "Login required");
        }
        return authService.getCurrentUserProfile(userId);
    }
}
