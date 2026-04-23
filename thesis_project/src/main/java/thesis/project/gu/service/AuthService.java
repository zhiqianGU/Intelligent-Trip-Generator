package thesis.project.gu.service;


import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thesis.project.gu.dto.UserRefreshToken;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.mapper.AppUserMapper;
import thesis.project.gu.mapper.UserCredentialMapper;
import thesis.project.gu.mapper.UserIdentifierMapper;
import thesis.project.gu.mapper.UserRefreshTokenMapper;
import thesis.project.gu.model.AppUser;
import thesis.project.gu.model.UserCredential;
import thesis.project.gu.model.UserIdentifier;
import thesis.project.gu.response.LoginResult;
import thesis.project.gu.util.JwtUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AuthService {

    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_ATTEMPT_WINDOW_MILLIS = 15 * 60 * 1000L;
    private static final String INVALID_LOGIN_MESSAGE = "Invalid username, email, phone number, or password";


    private final AppUserMapper appUserMapper;
    private final UserCredentialMapper credentialMapper;
    private final UserIdentifierMapper identifierMapper;
    private final UserRefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final CaptchaChallengeService captchaChallengeService;
    private final ConcurrentMap<String, LoginAttemptWindow> loginAttempts = new ConcurrentHashMap<>();


    public AuthService(AppUserMapper appUserMapper, UserCredentialMapper credentialMapper, UserIdentifierMapper identifierMapper, UserRefreshTokenMapper refreshTokenMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil, CaptchaChallengeService captchaChallengeService) {
        this.appUserMapper = appUserMapper;
        this.credentialMapper = credentialMapper;
        this.identifierMapper = identifierMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.captchaChallengeService = captchaChallengeService;
    }


    private static final String EMAIL_REGEX = "^[\\w.-]+@[\\w.-]+\\.[A-Za-z]{2,}$";
    private static final String PHONE_REGEX = "^\\+?\\d{7,15}$";

    /**
     * 娉ㄥ唽锛歭ogin 鍙互鏄偖绠辨垨鎵嬫満鍙凤紝name 鍙€?
     */
    @Transactional
    public void register(String login, String rawPassword, String name) {
        boolean isEmail = login != null && login.matches(EMAIL_REGEX);
        boolean isPhone = login != null && login.matches(PHONE_REGEX);
        if (!isEmail && !isPhone) {
            throw new NavigatorException(ErrorCode.PARAM_ERROR, "login 蹇呴』鏄湁鏁堢殑閭鎴栨墜鏈哄彿");
        }
        String type = isEmail ? "EMAIL" : "PHONE";

        // 1) login锛堥偖绠?鎵嬫満锛夊敮涓€
        if (identifierMapper.findByTypeAndIdentifier(type, login) != null) {
            throw new NavigatorException(ErrorCode.USER_EXISTS, "Username / Email / Mobile Phone Number has been used (鐢ㄦ埛鍚?閭/鎵嬫満鍙?宸茶浣跨敤)");
        }

        // 2) displayName 鍙€夊敮涓€锛堝鏋滀綘瑕佹敮鎸佲€滅敤鎴峰悕鐧诲綍鈥濓級
        String displayName = (name != null && !name.isBlank()) ? name.trim() : login;
        if (appUserMapper.existsByDisplayName(displayName)) {
            throw new NavigatorException(ErrorCode.USER_EXISTS, "璇ョ敤鎴峰悕宸茶鍗犵敤");
        }

        // 3) 鏂板缓鐢ㄦ埛
        AppUser u = new AppUser();
        u.setDisplayName(displayName);
        appUserMapper.insert(u);

        // 4) 鍐欏嚟璇?
        UserCredential c = new UserCredential();
        c.setUserId(u.getId());
        c.setPasswordAlgo("bcrypt");
        c.setPassword(passwordEncoder.encode(rawPassword));
        credentialMapper.insert(c);

        // 5) 缁戝畾鐧诲綍鏍囪瘑
        UserIdentifier iden = new UserIdentifier();
        iden.setUserId(u.getId());
        iden.setIdType(type);
        iden.setIdentifier(login);
        iden.setVerified(Boolean.FALSE);
        identifierMapper.insert(iden);
    }

    @Transactional
    public LoginResult login(String login, String rawPassword, boolean rememberMe, String userAgent, String ip, String challengeId, String challengeAnswer) {
        String attemptKey = loginAttemptKey(login, ip);
        assertLoginNotRateLimited(attemptKey);
        if (isChallengeRequired(attemptKey)) {
            captchaChallengeService.verifyRequired(challengeId, challengeAnswer);
        }

        UserIdentifier iden = identifierMapper.findByIdentifierAny(login);
        Long userId;
        if (iden != null) {
            userId = iden.getUserId();
        } else {
            AppUser namedUser = appUserMapper.findByDisplayName(login);
            userId = namedUser != null ? namedUser.getId() : null;
        }

        if (userId == null) {
            recordFailedLoginAttempt(attemptKey);
            throw new NavigatorException(ErrorCode.LOGIN_FAIL, INVALID_LOGIN_MESSAGE);
        }

        UserCredential cred = credentialMapper.findByUserId(userId);
        if (cred == null || !passwordEncoder.matches(rawPassword, cred.getPassword())) {
            recordFailedLoginAttempt(attemptKey);
            throw new NavigatorException(ErrorCode.LOGIN_FAIL, INVALID_LOGIN_MESSAGE);
        }

        AppUser user = appUserMapper.findById(userId);
        if (user == null) {
            recordFailedLoginAttempt(attemptKey);
            throw new NavigatorException(ErrorCode.LOGIN_FAIL, INVALID_LOGIN_MESSAGE);
        }

        String accessToken = jwtUtil.generateAccessToken(Map.of(
                "userId", userId,
                "username", user.getDisplayName()
        ));

        String refreshToken = jwtUtil.generateRefreshTokenPlaintext();
        String tokenHash = jwtUtil.hashRefreshToken(refreshToken);

        UserRefreshToken rt = new UserRefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(tokenHash);
        rt.setCreatedAt(LocalDateTime.now());
        rt.setExpiresAt(LocalDateTime.now().plusDays(rememberMe ? 30 : 1));
        rt.setDeviceInfo(userAgent);
        rt.setIpAddress(ip);
        refreshTokenMapper.insert(rt);

        loginAttempts.remove(attemptKey);
        return new LoginResult(accessToken, refreshToken, userId, user.getDisplayName(),rememberMe);
    }

    @Transactional
    public LoginResult refresh(String refreshTokenPlain, String userAgent, String ip) {
        String tokenHash = jwtUtil.hashRefreshToken(refreshTokenPlain);
        UserRefreshToken existing = refreshTokenMapper.findActiveByTokenHash(tokenHash);
        if (existing == null) {
            throw new NavigatorException(ErrorCode.UNAUTHORIZED, "Refresh token invalid");
        }

        Long userId = existing.getUserId();
        AppUser user = appUserMapper.findById(userId);
        boolean rememberMe = isRememberMeToken(existing);

        String newAccessToken = jwtUtil.generateAccessToken(Map.of(
                "userId", userId,
                "username", user.getDisplayName()
        ));

        // rotation
        String newRefreshToken = jwtUtil.generateRefreshTokenPlaintext();
        String newHash = jwtUtil.hashRefreshToken(newRefreshToken);

        UserRefreshToken next = new UserRefreshToken();
        next.setUserId(userId);
        next.setTokenHash(newHash);
        next.setCreatedAt(LocalDateTime.now());
        next.setExpiresAt(LocalDateTime.now().plusDays(rememberMe ? 30 : 1));
        next.setDeviceInfo(userAgent);
        next.setIpAddress(ip);
        refreshTokenMapper.insert(next);

        refreshTokenMapper.revokeById(existing.getId(), LocalDateTime.now());
        refreshTokenMapper.updateReplacedByTokenId(existing.getId(), next.getId());

        return new LoginResult(newAccessToken, newRefreshToken, userId, user.getDisplayName(), rememberMe);
    }

    @Transactional
    public void logout(String refreshTokenPlain) {
        String tokenHash = jwtUtil.hashRefreshToken(refreshTokenPlain);
        UserRefreshToken existing = refreshTokenMapper.findActiveByTokenHash(tokenHash);
        if (existing != null) {
            refreshTokenMapper.revokeById(existing.getId(), LocalDateTime.now());
        }
    }

    public void clearLoginAttempts(String login, String ip) {
        loginAttempts.remove(loginAttemptKey(login, ip));
    }

    public Map<String, Object> getCurrentUserProfile(Long userId) {
        AppUser user = appUserMapper.findById(userId);
        if (user == null) {
            throw new NavigatorException(ErrorCode.UNAUTHORIZED, "Login required");
        }

        List<UserIdentifier> identifiers = identifierMapper.findByUserId(userId);
        String email = null;
        String phone = null;
        for (UserIdentifier identifier : identifiers) {
            if ("EMAIL".equalsIgnoreCase(identifier.getIdType()) && email == null) {
                email = identifier.getIdentifier();
            } else if ("PHONE".equalsIgnoreCase(identifier.getIdType()) && phone == null) {
                phone = identifier.getIdentifier();
            }
        }

        return Map.of(
                "userId", user.getId(),
                "username", user.getDisplayName(),
                "email", email == null ? "" : email,
                "phone", phone == null ? "" : phone,
                "loggedIn", true
        );
    }

    private boolean isRememberMeToken(UserRefreshToken token) {
        if (token.getCreatedAt() == null || token.getExpiresAt() == null) {
            return true;
        }
        return token.getCreatedAt().plusDays(2).isBefore(token.getExpiresAt());
    }

    private void assertLoginNotRateLimited(String key) {
        LoginAttemptWindow current = loginAttempts.get(key);
        long now = System.currentTimeMillis();
        if (current == null) {
            return;
        }
        if (now - current.windowStartedAtMillis > LOGIN_ATTEMPT_WINDOW_MILLIS) {
            loginAttempts.remove(key, current);
            return;
        }
        if (current.failedAttempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
            throw new NavigatorException(
                    ErrorCode.TOO_MANY_LOGIN_ATTEMPTS,
                    "Too many failed login attempts. Please try again later."
            );
        }
    }

    private boolean isChallengeRequired(String key) {
        LoginAttemptWindow current = loginAttempts.get(key);
        long now = System.currentTimeMillis();
        if (current == null) {
            return false;
        }
        if (now - current.windowStartedAtMillis > LOGIN_ATTEMPT_WINDOW_MILLIS) {
            loginAttempts.remove(key, current);
            return false;
        }
        return current.failedAttempts > 0;
    }

    private void recordFailedLoginAttempt(String key) {
        long now = System.currentTimeMillis();
        loginAttempts.compute(key, (ignored, current) -> {
            if (current == null || now - current.windowStartedAtMillis > LOGIN_ATTEMPT_WINDOW_MILLIS) {
                return new LoginAttemptWindow(1, now);
            }
            return new LoginAttemptWindow(current.failedAttempts + 1, current.windowStartedAtMillis);
        });
    }

    private String loginAttemptKey(String login, String ip) {
        String normalizedLogin = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
        String normalizedIp = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        return normalizedIp + "|" + normalizedLogin;
    }

    private record LoginAttemptWindow(int failedAttempts, long windowStartedAtMillis) {
    }

}
