package thesis.project.gu.idem;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Aspect
@Component
@Order(0) // 越小越靠前，尽早拦截
public class IdempotencyAspect {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private static final String HDR = "Idempotency-Key";

    public IdempotencyAspect( StringRedisTemplate redis, ObjectMapper objectMapper){

        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(anno)")
    public Object around(ProceedingJoinPoint pjp, Idempotent anno) throws Throwable {
        HttpServletRequest req = currentRequest();
        String path = (req != null) ? req.getRequestURI() : pjp.getSignature().toShortString();
        Duration ttl = Duration.ofSeconds(anno.resultTtlSeconds());
        // 1) 取幂等令牌（必须携带；你也可改成允许后备指纹）
        String token = (req != null) ? req.getHeader(HDR) : null;

        if (token == null || token.isBlank()) {
            return ResponseEntity.status(428) // 428 Precondition Required
                    .body(new Error("IDEMPOTENCY_KEY_REQUIRED", "缺少请求头: " + HDR));
        }

        // 2) 归一化用户维度（建议你的 JwtAuthFilter 把 principal 设置为 Long userId）
        String userPart = String.valueOf(currentUserId());

        // 3) 组合Redis Key
        String baseKey = "idem:" + anno.namespace() + ":" + userPart + ":" + safe(token) + ":" + safe(path);
        String stateKey = baseKey + ":state"; // PENDING / DONE
        String respKey  = baseKey + ":resp";  // 序列化后的响应
        String reqKey = baseKey + ":req";
        String fp = payloadFingerprint(pjp);
        String oldFp = redis.opsForValue().get(reqKey);
        if (oldFp != null && !oldFp.equals(fp)) {
            return ResponseEntity.status(409)
                    .body(new Error("IDEMPOTENCY_KEY_CONFLICT", "Same Idempotency-Key with different payload"));
        }



        // 4) 命中已有结果：直接返回同一响应
        String cached = redis.opsForValue().get(respKey);
        if (cached != null) {
            return deserialize(cached, pjp);
        }

        // 5) 抢占令牌：SET NX
        Boolean first = redis.opsForValue().setIfAbsent(stateKey, "PENDING", Duration.ofSeconds(anno.ttlSeconds()));
        if (Boolean.FALSE.equals(first)) {
            // 已有人在处理，尝试短暂等待复用结果
            long wait = anno.waitMillis();
            if (wait > 0) {
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < wait) {
                    Thread.sleep(50);
                    String resp2 = redis.opsForValue().get(respKey);
                    if (resp2 != null) return deserialize(resp2, pjp);
                }
            }
            // 仍无结果：返回 202，让客户端稍后重试
            return ResponseEntity.accepted()
                    .body(new Error("DUPLICATE_IN_PROGRESS", "相同请求正在处理，请稍后重试"));
        }

        // 6) 真正执行业务
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            // 失败释放占坑
            redis.delete(stateKey);
            throw ex;
        }

        // 7) 持久化响应，方便后续相同请求直接返回
        try {
            String json = objectMapper.writeValueAsString(result == null ? new VoidMarker() : result);
            ttl = Duration.ofSeconds(anno.resultTtlSeconds());
            redis.opsForValue().set(respKey, json, ttl);
            redis.opsForValue().set(stateKey, "DONE", ttl);
            redis.opsForValue().set(reqKey, fp, ttl);
        } catch (Exception e) {
            log.warn("Idempotency store resp failed: {}", e.getMessage());
            // 即使存失败，也不影响本次返回
        }
        return result;
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes sra) return sra.getRequest();
        return null;
    }

    private long currentUserId() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return 0L;
            Object p = auth.getPrincipal();
            if (p instanceof Long l) return l;
            if (p instanceof String s) {
                if ("anonymousUser".equalsIgnoreCase(s)) return 0L;
                byte[] sha = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
                // 前8字节转long（大端）
                long x = 0;
                for (int i = 0; i < 8; i++) x = (x << 8) | (sha[i] & 0xff);
                return x == 0L ? 1L : x; // 避免与匿名0混淆，可选
            }
        } catch (Exception ignore) {}
        return 0L;
    }

    private Object deserialize(String json, ProceedingJoinPoint pjp) throws Exception {
        var sig = (org.aspectj.lang.reflect.MethodSignature) pjp.getSignature();
        var rt  = sig.getReturnType();
        if (rt == Void.TYPE || rt == Void.class) return null;

        // void 占位
        if (json.startsWith("{") && json.contains("\"_void\":true")) return null;

        if (rt == String.class) {
            return objectMapper.readValue(json, String.class);
        }

        // 支持泛型返回
        var gType = sig.getMethod().getGenericReturnType();
        var javaType = objectMapper.getTypeFactory().constructType(gType);
        return objectMapper.readValue(json, javaType);
    }

    private String safe(String s) {
        // 避免 key 过长 / 不可见字符：hash 一下
        if (s == null) return "null";
        if (s.length() > 64) return DigestUtils.sha256Hex(s);
        return s.replaceAll("\\s+", "_");
    }
    private String payloadFingerprint(ProceedingJoinPoint pjp) throws Exception {
        var sig = (org.aspectj.lang.reflect.MethodSignature) pjp.getSignature();
        var params = sig.getMethod().getParameters();
        Object bodyArg = null;
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(org.springframework.web.bind.annotation.RequestBody.class)) {
                bodyArg = pjp.getArgs()[i];
                break;
            }
        }
        if (bodyArg == null) return "no-body";
        // 规范化序列化，避免字段顺序影响
        String canon = objectMapper.writeValueAsString(bodyArg);
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(canon);
    }

    // 占位，表示“void 响应”
    private record VoidMarker(boolean _void) { private VoidMarker() { this(true); } }

    public record Error(String code, String message) {}
}
