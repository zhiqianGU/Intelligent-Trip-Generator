package thesis.project.gu.config;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.time.Duration;


@Getter
@Configuration
@EnableConfigurationProperties(AmapProperties.class)
public class WebClientConfig {
    private final AmapProperties props;
    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    public WebClientConfig(AmapProperties props) {
        this.props = props;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {


        return builder
                .build();
    }

    @Bean
    public ClientHttpRequestInterceptor headerLoggerInterceptor() {
        return new ClientHttpRequestInterceptor() {
            @NotNull
            @Override
            public ClientHttpResponse intercept(@NotNull org.springframework.http.HttpRequest request,
                                                @NotNull byte[] body,
                                                @NotNull ClientHttpRequestExecution execution) throws IOException {
                log.debug("\n---- Outgoing Request Start ----\nHeaders:\n{}\n---- Outgoing Request End ----", request.getHeaders());
                return execution.execute(request, body);
            }
        };
    }


}
