package com.example.agent.temporal;

import java.util.List;

import com.example.agent.agent.AnalystAgent;
import com.example.agent.agent.PlanStep;
import com.example.agent.agent.ResearchAgent;
import com.example.agent.agent.StepResult;
import com.example.agent.agent.WriterAgent;
import com.example.agent.core.AgentTrace;

import org.springframework.stereotype.Service;

/**
 * Dispatches a single plan step to the matching agent. Shared by the synchronous
 * {@code AgentOrchestrator} and the durable Temporal run, so both paths execute
 * steps identically.
 */
@Service
public class AgentStepExecutor {

    private final ResearchAgent researcher;
    private final AnalystAgent analyst;
    private final WriterAgent writer;

    public AgentStepExecutor(ResearchAgent researcher, AnalystAgent analyst, WriterAgent writer) {
        this.researcher = researcher;
        this.analyst = analyst;
        this.writer = writer;
    }

    public String execute(PlanStep step,
                          List<StepResult> priorResults,
                          AgentTrace trace,
                          String goal) {
        return switch (step.agent()) {
            case "ANALYST" -> analyst.run(goal, step.instruction(), priorResults, trace);
            case "WRITER" -> writer.run(goal, step.instruction(), priorResults, trace);
            default -> researcher.run(goal, step.instruction(), priorResults, trace);
        };
    }
}
