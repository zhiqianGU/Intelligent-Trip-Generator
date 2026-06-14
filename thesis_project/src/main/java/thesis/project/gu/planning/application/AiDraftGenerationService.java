package thesis.project.gu.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import thesis.project.gu.planning.ai.TripAiService;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;

import java.util.List;
import java.util.Map;

@Service
public class AiDraftGenerationService {
    private final TripAiService aiService;
    private final ObjectMapper objectMapper;

    public AiDraftGenerationService(TripAiService aiService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    public String generateInitialRaw(
            CreatePlanReq req,
            PlanGenerationModeResolver.GenerationMode mode
    ) throws Exception {
        return mode != null && mode.phasedGeneration()
                ? aiService.generatePlanRawPhased(req)
                : aiService.generatePlanRaw(req);
    }

    public String regenerateInvalidJsonRaw(
            CreatePlanReq req,
            boolean phasedGeneration,
            JsonProcessingException parseFailure,
            String skeletonHints
    ) throws Exception {
        if (phasedGeneration) {
            return aiService.generatePlanRawPhased(req);
        }
        return aiService.regeneratePlanRaw(
                req,
                invalidJsonRetryInstruction(parseFailure),
                skeletonHints
        );
    }

    public String regenerateWholePlanRaw(
            CreatePlanReq req,
            String retryInstruction,
            String skeletonHints
    ) throws Exception {
        return aiService.regeneratePlanRaw(req, retryInstruction, skeletonHints);
    }

    public PlanDraftResponse regeneratePlanDaysPhased(
            CreatePlanReq req,
            PlanDraftResponse failedDraft,
            List<Integer> targetDayIndexes,
            Map<Integer, String> retryInstructionsByDay
    ) throws Exception {
        return aiService.regeneratePlanDaysPhased(req, failedDraft, targetDayIndexes, retryInstructionsByDay);
    }

    public PlanDraftResponse parseRawDraft(String raw) throws JsonProcessingException {
        if (raw == null) {
            throw new IllegalArgumentException("AI raw response is null");
        }
        return objectMapper.readValue(stripMarkdownFence(raw), PlanDraftResponse.class);
    }

    public String serializeDraft(PlanDraftResponse draft) throws JsonProcessingException {
        return objectMapper.writeValueAsString(draft);
    }

    public String shortRawPreview(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = raw.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return compact;
        }
        return compact.substring(0, 237) + "...";
    }

    private String stripMarkdownFence(String raw) {
        return raw.strip()
                .replaceAll("^```[a-zA-Z]*\\s*", "")
                .replaceAll("```\\s*$", "");
    }

    private String invalidJsonRetryInstruction(JsonProcessingException parseFailure) {
        String message = parseFailure == null ? "unknown JSON parse error" : parseFailure.getOriginalMessage();
        return "The previous itinerary response was not valid JSON and could not be parsed. "
                + "Return the complete itinerary again as strict JSON only, matching the required schema exactly. "
                + "Do not include Markdown fences, comments, explanations, trailing commas, or text outside JSON. "
                + "Parser error to fix: " + message;
    }
}
