package thesis.project.gu.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    private static final long EXP_MILLIS = Duration.ofHours(4).toMillis();
    private static final long ACCESS_EXP_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Key key;

    public JwtUtil(@Value("${app.jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret is not configured");
        }
        byte[] bytes = secret.trim().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String generate(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXP_MILLIS))
                .signWith(key)
                .compact();
    }

    public String generateWithClaims(Map<String, Object> claims) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + EXP_MILLIS);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith((SecretKey) key)
                .clockSkewSeconds(60)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String generateAccessToken(Map<String, Object> claims) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ACCESS_EXP_MILLIS);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public String generateRefreshTokenPlaintext() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashRefreshToken(String token) {
        return DigestUtils.sha256Hex(token);
    }

    public <T> T getClaim(String token, Function<Claims, T> resolver) {
        Claims claims = parseClaims(token);
        return resolver.apply(claims);
    }

    public String validateAndGetUser(String token) {
        return getClaim(token, Claims::getSubject);
    }
}
