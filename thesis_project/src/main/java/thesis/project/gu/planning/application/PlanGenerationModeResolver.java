package thesis.project.gu.planning.application;

import org.springframework.stereotype.Component;
import thesis.project.gu.planning.api.dto.CreatePlanReq;

import java.util.Locale;

@Component
public class PlanGenerationModeResolver {
    private static final String PHASED_PIPELINE_FLAG = "itrip.plan.phased.enabled";
    private static final int AUTO_PHASED_MIN_DAYS = 3;

    public GenerationMode resolve(CreatePlanReq req) {
        boolean localFast = isLocalFast(req);
        return new GenerationMode(localFast, !localFast && isPhasedGenerationEnabled(req));
    }

    public boolean isLocalFast(CreatePlanReq req) {
        return req != null
                && req.mainModel() != null
                && "local-fast".equals(req.mainModel().trim().toLowerCase(Locale.ROOT));
    }

    public boolean isPhasedGenerationEnabled(CreatePlanReq req) {
        if (Boolean.parseBoolean(System.getProperty(PHASED_PIPELINE_FLAG, "false"))) {
            return true;
        }
        int days = req == null ? 0 : req.days();
        return days >= AUTO_PHASED_MIN_DAYS;
    }

    public record GenerationMode(boolean localFast, boolean phasedGeneration) {}
}
