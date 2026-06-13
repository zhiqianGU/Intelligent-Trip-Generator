package thesis.project.gu;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import thesis.project.gu.planning.ai.AiMockProperties;
import thesis.project.gu.planning.ai.AiProperties;
import thesis.project.gu.infrastructure.external.routing.AmapProperties;
import thesis.project.gu.infrastructure.cache.CacheCleanupProperties;
import thesis.project.gu.infrastructure.external.google.GoogleGeocodingProperties;
import thesis.project.gu.infrastructure.external.google.GooglePlacesProperties;
import thesis.project.gu.weather.infrastructure.WeatherApiProperties;

@SpringBootApplication
@EnableConfigurationProperties({AmapProperties.class, AiProperties.class, AiMockProperties.class, CacheCleanupProperties.class, GooglePlacesProperties.class, GoogleGeocodingProperties.class, WeatherApiProperties.class})
@MapperScan({
        "thesis.project.gu.user.persistence",
        "thesis.project.gu.planhistory.persistence",
        "thesis.project.gu.catalog.persistence",
        "thesis.project.gu.observability.persistence",
        "thesis.project.gu.infrastructure.external.google.cache"
})
@EnableCaching
public class ThesisProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThesisProjectApplication.class, args);
    }

}
