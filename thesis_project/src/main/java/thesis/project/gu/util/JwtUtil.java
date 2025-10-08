package thesis.project.gu.util;


import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;


@Component
public class JwtUtil {

    // 256-bit key，实际应从 KMS 或 env 注入
    private static final String SECRET = "replace-with-256bit-secret-replace-with-256bit";
    private static final Key KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private static final long EXP_MILLIS = Duration.ofHours(4).toMillis();

    /** 只存 subject（用户名）的老接口 */
    public String generate(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXP_MILLIS))
                .signWith(KEY)
                .compact();
    }

    /** 可存任意自定义 claims自定义声明，比如 userId、username 等 */
    public String generateWithClaims(Map<String, Object> claims) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + EXP_MILLIS);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(exp)
                .signWith(KEY)
                .compact();
    }

    /** 解析所有 claims自定义声明，抛出异常表示 token 无效 */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith((SecretKey) KEY)
                .clockSkewSeconds(60)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 通用的取单个 claim 方法 */
    public <T> T getClaim(String token, Function<Claims, T> resolver) {
        Claims claims = parseClaims(token);
        return resolver.apply(claims);
    }

    /** 验证 token 并返回 subject（用户名） */
    public String validateAndGetUser(String token) {
        return getClaim(token, Claims::getSubject);
    }
}

