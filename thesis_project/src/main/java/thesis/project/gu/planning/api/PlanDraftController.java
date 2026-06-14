package thesis.project.gu.planning.api;

import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.application.CopyPolishService;
import thesis.project.gu.routing.prewarm.PlanPrewarmService;
import thesis.project.gu.planning.application.PlanProcessorService;
import thesis.project.gu.planhistory.application.PlanService;
import thesis.project.gu.observability.application.RuntimeMetricsService;
import thesis.project.gu.planning.ai.TripAiService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanDraftController {
    private final PlanService planService;
    private final TripAiService aiService;
    private final CacheManager cacheManager;
    private final RuntimeMetricsService runtimeMetricsService;
    private final PlanPrewarmService planPrewarmService;
    private final PlanProcessorService planProcessorService;
    private final CopyPolishService copyPolishService;

    public PlanDraftController(
            PlanService planService,
            TripAiService aiService,
            CacheManager cacheManager,
            RuntimeMetricsService runtimeMetricsService,
            PlanPrewarmService planPrewarmService,
            PlanProcessorService planProcessorService,
            CopyPolishService copyPolishService
    ) {
        this.planService = planService;
        this.aiService = aiService;
        this.cacheManager = cacheManager;
        this.runtimeMetricsService = runtimeMetricsService;
        this.planPrewarmService = planPrewarmService;
        this.planProcessorService = planProcessorService;
        this.copyPolishService = copyPolishService;
    }

    @PostMapping(value = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PlanDraftResponse draft(
            @RequestBody @Valid CreatePlanReq req,
            @RequestHeader(name = "X-Defer-Copy-Polish", required = false) Boolean deferCopyPolish
    )
            throws Exception {
        long startedAt = System.currentTimeMillis();
        boolean redisHit = isAiPlanCacheHit(req);
        try {
            PlanDraftResponse result = planProcessorService.generateDraft(req, redisHit, Boolean.TRUE.equals(deferCopyPolish));
            runtimeMetricsService.recordPlanGenerateRequest("draft", redisHit, System.currentTimeMillis() - startedAt, true);
            return result;
        } catch (NoApiKeyException | InputRequiredException | JsonProcessingException e) {
            runtimeMetricsService.recordPlanGenerateRequest("draft", redisHit, System.currentTimeMillis() - startedAt, false);
            throw e;
        } catch (RuntimeException e) {
            runtimeMetricsService.recordPlanGenerateRequest("draft", redisHit, System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @PostMapping("/raw")
    public Map<String, Object> generateRaw(
            @RequestBody @Valid CreatePlanReq req,
            @RequestHeader(name = "X-Defer-Copy-Polish", required = false) Boolean deferCopyPolish,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) throws Exception {
        long startedAt = System.currentTimeMillis();
        boolean redisHit = isAiPlanCacheHit(req);
        try {
            PlanDraftResponse draft = planProcessorService.generateDraft(req, redisHit, Boolean.TRUE.equals(deferCopyPolish));

            String preview;
            try {
                preview = aiService.render(draft, req.budget());
            } catch (Exception e) {
                preview = "(Tip) Preview rendering failed, but the structured draft was parsed successfully.";
            }

            Long aiRawId = 666666L;
            Long planId = null;
            if (userId != null) {
                planId = aiService.saveDraftPlan(userId, draft, req.budget(), null, req.style(), req.pace(), req.departureDate());
                if (planId != null) {
                    planPrewarmService.prewarmPlanAsync(planId, draft.city());
                }
            }

            runtimeMetricsService.recordPlanGenerateRequest("raw", redisHit, System.currentTimeMillis() - startedAt, true);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("aiRawId", aiRawId);
            response.put("planId", planId);
            response.put("draft", draft);
            response.put("preview", preview);
            return response;
        } catch (Exception e) {
            runtimeMetricsService.recordPlanGenerateRequest("raw", redisHit, System.currentTimeMillis() - startedAt, false);
            throw e;
        }
    }

    @PostMapping(value = "/copy-polish", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PlanDraftResponse copyPolish(
            @RequestBody CopyPolishRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = false) Long userId
    ) {
        PlanDraftResponse draft = request == null ? null : request.draft();
        if (draft == null) {
            return null;
        }
        PlanDraftResponse polished = copyPolishService.applyCopyPolishPatch(
                draft,
                planProcessorService::finalizeCopyPolishDraft
        );
        Long planId = request == null ? null : request.planId();
        if (userId != null && planId != null && planId > 0 && polished != null) {
            planService.updatePlanCopy(userId, planId, polished);
        }
        return polished;
    }

    private boolean isAiPlanCacheHit(CreatePlanReq req) {
        Cache cache = cacheManager.getCache("ai_plan_raw");
        if (cache == null) return false;
        String key = planService.buildAiPlanCacheKey(req);
        return cache.get(key) != null;
    }

    public record CopyPolishRequest(Long planId, PlanDraftResponse draft) {}
}
