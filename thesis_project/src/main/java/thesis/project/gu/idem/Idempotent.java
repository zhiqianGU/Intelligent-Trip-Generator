package thesis.project.gu.idem;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
    /** 业务命名空间，方便区分不同接口 */
    String namespace() default "default";

    /** 幂等令牌（占坑）的过期秒数：处理超时自动释放 */
    long ttlSeconds() default 300; // 5分钟

    /** 结果缓存保存秒数：重复请求可直接返回同一响应 */
    long resultTtlSeconds() default 86400; // 24小时

    /** 如果发现同 Key 正在处理，最多等待毫秒以复用结果（0=不等直接返回202） */
    long waitMillis() default 800;
}
