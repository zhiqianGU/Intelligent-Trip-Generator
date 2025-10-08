package thesis.project.gu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;



@ConfigurationProperties(prefix = "amap")
public record AmapProperties(String key, String baseUrl) {}
