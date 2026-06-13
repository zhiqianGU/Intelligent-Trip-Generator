package thesis.project.gu.infrastructure.cache;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import thesis.project.gu.planning.api.dto.CreatePlanReq;

import java.util.stream.Collectors;

@Configuration
public class CacheKeyConfig {

    @Bean("aiPlanKeyGen")
    public KeyGenerator aiPlanKeyGen() {
        return (target, method, params) -> {
            if (params.length == 0 || params[0] == null) return "empty";
            var req = (CreatePlanReq) params[0];

            String styles = (req.style()==null) ? "" :
                    req.style().stream()
                            .map(s -> s==null? "" : s.trim().toLowerCase())
                            .sorted()
                            .collect(Collectors.joining(","));
            int adults = (req.party()!=null && req.party().adults()!=null) ? req.party().adults() : 1;
            int kids   = (req.party()!=null && req.party().kids()!=null)   ? req.party().kids()   : 0;

            String raw = "%s|%d|%s|%d|%d|%s|%s|%s".formatted(
                    n(req.city()).toLowerCase(),
                    req.days(),
                    req.budget()==null? "" : req.budget(),
                    adults, kids,
                    n(req.pace()).toLowerCase(),
                    n(req.mainModel()).toLowerCase(),
                    styles
            );
            return Integer.toHexString(raw.hashCode()); // 稳定且短的 key
        };
    }

    private static String n(String s){ return s==null? "" : s.trim(); }
}
