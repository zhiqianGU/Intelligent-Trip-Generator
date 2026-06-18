package thesis.project.gu.planning.application;

import thesis.project.gu.planning.domain.PlanningAgentInput;
import thesis.project.gu.planning.domain.PlanningAgentOutput;

public interface PlanningAgent {
    PlanningAgentOutput plan(PlanningAgentInput input);
}
