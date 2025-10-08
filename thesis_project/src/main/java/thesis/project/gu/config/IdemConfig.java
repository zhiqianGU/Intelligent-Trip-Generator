package thesis.project.gu.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableAspectJAutoProxy
public class IdemConfig {
    // ObjectMapper / StringRedisTemplate 通常已在项目里配置，无需重复声明
}
