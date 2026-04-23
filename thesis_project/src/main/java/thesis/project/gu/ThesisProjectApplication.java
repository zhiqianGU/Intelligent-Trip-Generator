package thesis.project.gu;

import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import thesis.project.gu.config.AiMockProperties;
import thesis.project.gu.config.AiProperties;
import thesis.project.gu.config.AmapProperties;
import thesis.project.gu.config.CacheCleanupProperties;
import thesis.project.gu.config.GoogleGeocodingProperties;
import thesis.project.gu.config.GooglePlacesProperties;
import thesis.project.gu.config.WeatherApiProperties;

@SpringBootApplication
@EnableConfigurationProperties({AmapProperties.class, AiProperties.class, AiMockProperties.class, CacheCleanupProperties.class, GooglePlacesProperties.class, GoogleGeocodingProperties.class, WeatherApiProperties.class})
@MapperScan("thesis.project.gu.mapper")
@EnableCaching
public class ThesisProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThesisProjectApplication.class, args);
    }

}
