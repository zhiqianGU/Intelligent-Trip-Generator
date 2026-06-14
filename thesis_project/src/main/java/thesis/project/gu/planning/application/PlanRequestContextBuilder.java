package thesis.project.gu.planning.application;

import org.springframework.stereotype.Component;
import thesis.project.gu.planning.api.dto.CreatePlanReq;

@Component
public class PlanRequestContextBuilder {
    private final PlanRequestNormalizer normalizer;
    private final PlanGenerationModeResolver modeResolver;

    public PlanRequestContextBuilder(
            PlanRequestNormalizer normalizer,
            PlanGenerationModeResolver modeResolver
    ) {
        this.normalizer = normalizer;
        this.modeResolver = modeResolver;
    }

    public PlanRequestContext build(CreatePlanReq req, boolean redisHit, boolean deferCopyPolish) {
        CreatePlanReq normalizedReq = normalizer.normalize(req);
        PlanGenerationModeResolver.GenerationMode mode = modeResolver.resolve(normalizedReq);
        return new PlanRequestContext(
                normalizedReq,
                redisHit,
                deferCopyPolish,
                mode,
                normalizedReq == null ? null : normalizedReq.city(),
                normalizedReq == null ? 0 : normalizedReq.days(),
                normalizedReq == null ? "normal" : normalizedReq.pace(),
                normalizedReq == null ? 0 : normalizer.normalizeKids(normalizedReq.party()),
                normalizedReq == null ? null : normalizedReq.mainModel()
        );
    }

    public record PlanRequestContext(
            CreatePlanReq request,
            boolean redisHit,
            boolean deferCopyPolish,
            PlanGenerationModeResolver.GenerationMode mode,
            String city,
            int days,
            String pace,
            int kids,
            String mainModel
    ) {
    }
}
