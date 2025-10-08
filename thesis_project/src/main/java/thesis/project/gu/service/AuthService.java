package thesis.project.gu.service;


import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;
import thesis.project.gu.mapper.AppUserMapper;
import thesis.project.gu.mapper.UserCredentialMapper;
import thesis.project.gu.mapper.UserIdentifierMapper;
import thesis.project.gu.model.AppUser;
import thesis.project.gu.model.UserCredential;
import thesis.project.gu.model.UserIdentifier;
import thesis.project.gu.util.JwtUtil;

import java.util.Map;

@Service
public class AuthService {



    private final AppUserMapper appUserMapper;
    private final UserCredentialMapper credentialMapper;
    private final UserIdentifierMapper identifierMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;


    public AuthService(AppUserMapper appUserMapper, UserCredentialMapper credentialMapper, UserIdentifierMapper identifierMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.appUserMapper = appUserMapper;
        this.credentialMapper = credentialMapper;
        this.identifierMapper = identifierMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }


    private static final String EMAIL_REGEX = "^[\\w.-]+@[\\w.-]+\\.[A-Za-z]{2,}$";
    private static final String PHONE_REGEX = "^\\+?\\d{7,15}$";

    /**
     * 注册：login 可以是邮箱或手机号，name 可选
     */
    @Transactional
    public void register(String login, String rawPassword, String name) {
        boolean isEmail = login != null && login.matches(EMAIL_REGEX);
        boolean isPhone = login != null && login.matches(PHONE_REGEX);
        if (!isEmail && !isPhone) {
            throw new NavigatorException(ErrorCode.PARAM_ERROR, "login 必须是有效的邮箱或手机号");
        }
        String type = isEmail ? "EMAIL" : "PHONE";

        // 1) login（邮箱/手机）唯一
        if (identifierMapper.findByTypeAndIdentifier(type, login) != null) {
            throw new NavigatorException(ErrorCode.USER_EXISTS, "该邮箱或手机号已被注册");
        }

        // 2) displayName 可选唯一（如果你要支持“用户名登录”）
        String displayName = (name != null && !name.isBlank()) ? name.trim() : login;
        if (appUserMapper.existsByDisplayName(displayName)) {
            throw new NavigatorException(ErrorCode.USER_EXISTS, "该用户名已被占用");
        }

        // 3) 新建用户
        AppUser u = new AppUser();
        u.setDisplayName(displayName);
        appUserMapper.insert(u);

        // 4) 写凭证
        UserCredential c = new UserCredential();
        c.setUserId(u.getId());
        c.setPasswordAlgo("bcrypt");
        c.setPassword(passwordEncoder.encode(rawPassword));
        credentialMapper.insert(c);

        // 5) 绑定登录标识
        UserIdentifier iden = new UserIdentifier();
        iden.setUserId(u.getId());
        iden.setIdType(type);
        iden.setIdentifier(login);
        iden.setVerified(Boolean.FALSE);
        identifierMapper.insert(iden);
    }

    public String login(String login, String rawPassword) {
        // 允许：邮箱/手机/用户名 任一登录
        UserIdentifier iden = identifierMapper.findByIdentifierAny(login);
        Long userId;
        if (iden != null) {
            userId = iden.getUserId();
        } else {
            // 回退：按用户名找
            var user = appUserMapper.findByDisplayName(login);
            userId = (user != null) ? user.getId() : null;
        }

        if (userId == null) {
            throw new NavigatorException(ErrorCode.LOGIN_FAIL, "用户名/邮箱/手机号 或密码错误");
        }

        var cred = credentialMapper.findByUserId(userId);
        if (cred == null || !passwordEncoder.matches(rawPassword, cred.getPassword())) {
            throw new NavigatorException(ErrorCode.LOGIN_FAIL, "用户名/邮箱/手机号 或密码错误");
        }

        var user = appUserMapper.findById(userId);
        return jwtUtil.generateWithClaims(Map.of(
                "userId", userId,
                "username", user.getDisplayName()
        ));
    }

}