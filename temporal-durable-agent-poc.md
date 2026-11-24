# Temporal durable agent run — POC plan

Status: proposed, not implemented  
Scope: `agent` module first, `bank-agent` as phase 3  
Goal: a multi-step agent run that survives a JVM crash, can report the last successful step, and continues from the next step without re-running completed work

---

## 1. Context

The repository contains four Spring Boot demos:

| Module | Current orchestration | Durability today |
|---|---|---|
| `chat` | single chat + RAG advisor | JDBC chat memory only |
| `agent` | planner → researcher / analyst / writer / critic, sequential or parallel | none; in-memory `AgentTrace` |
| `bank-agent` | router → domain agents → writer / critic, async runs | in-memory `RunRegistry`; durable `JobService` for scheduled jobs only |
| `embabel-stock-agent` | Embabel `ScatterGather` research | none configured |

The user selected **Temporal** as the reference implementation for durable run/resume.

The POC answers three questions:

1. After a crash, how do we know the last step that actually succeeded?
2. How does the run continue from the next step and finish?
3. How do we observe the run while it is live and after it has crashed?

---

## 2. Decision summary

| Decision | Choice |
|---|---|
| Durable execution engine | Temporal Java SDK |
| First module | `agent` |
| Orchestration style | one Temporal workflow per run; one activity per phase/step |
| Step durability | activity boundary; completed activities are replayed from history, not re-executed |
| Human approval | `@SignalMethod` + `Workflow.await` |
| Status API | `@QueryMethod` on the workflow |
| Side-effect safety | deterministic idempotency key derived from the Temporal activity ID |
| Observability | Temporal UI + Micrometer metrics + OpenTelemetry traces |
| Spring AI integration | optional phase 3 (`io.temporal:spring-ai`) for durable model/tool loops |

The core POC does **not** require `io.temporal:spring-ai`. It uses the existing Spring AI `ChatClient` inside activities. The integration is added later for a second, finer-grained demo.

---

## 3. Verified compatibility

Checked against the published artifacts, not only documentation.

| Component | Version | Notes |
|---|---|---|
| `io.temporal:temporal-spring-boot-starter` | `1.41.1` | ships `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; Boot 4 compatible |
| `io.temporal:temporal-sdk` | `1.41.1` | default Jackson converter includes `JavaTimeModule`; `Instant` payloads work |
| `io.temporal:spring-ai` | `0.1.1` | built against Spring AI `2.0.1` / Boot `4.0.8`; README says Boot `4.1.1` is tested |
| This repo | Boot `4.1.2`, Spring AI `2.0.2`, Java `21` | compatible |

Useful starter behavior found in the JAR:

- `MetricsScopeAutoConfiguration` creates a Temporal metrics scope from any Micrometer `MeterRegistry`.
- `OpenTracingAutoConfiguration` creates a Temporal `Tracer` from an `io.opentelemetry.api.OpenTelemetry` bean.

So Temporal metrics and traces are configuration, not custom plumbing.

### Version pinning

Pin Temporal versions in the module POM. Do not rely on Spring Boot dependency management for Temporal artifacts.

```xml
<properties>
    <temporal.version>1.41.1</temporal.version>
    <temporal-spring-ai.version>0.1.1</temporal-spring-ai.version>
</properties>
```

---

## 4. Why Temporal solves the stated problem

Temporal persists a workflow event history. A workflow is deterministic code that is replayed from that history. Activities are the only place where I/O happens.

The crash semantics are:

| Crash point | Temporal behavior |
|---|---|
| Worker dies while an activity is running | activity task times out or heartbeat expires; the activity is retried; earlier steps remain completed |
| Worker dies after the activity returned but before the workflow task completed | the activity result is already in history; replay uses it and does **not** invoke the activity again |
| Worker dies after the workflow task completed | replay skips the step completely and schedules the next one |
| Whole application/cluster dies | history is in Temporal persistence; workers resume open workflows on restart |
| Workflow is waiting for a human signal | `Workflow.await(...)` survives restart; a signal sent later still resumes it |

The guarantee is **at-least-once activity execution with idempotent effects**, not exactly-once. Every side effect must carry an idempotency key.

### What the user sees

- `GET /api/agent/runs/{runId}` returns `status`, `currentStep`, `lastCompletedStep`, and per-step state.
- During a crash, the application API is down, but the same state is visible in Temporal UI or with `temporal workflow show`.
- After restart, the workflow continues automatically. No boot scanner and no manual resume endpoint are required.
- If a manual retry is wanted after a non-retryable failure, it is a signal or a new workflow, not a re-implementation of the orchestration loop.

### Temporal vs a custom JDBC ledger

| Requirement | Custom ledger | Temporal |
|---|---|---|
| know last successful step | `agent_step` row | `ActivityTaskCompleted` event + workflow state |
| resume after crash | boot scanner + resume endpoint + lease | automatic replay and activity retry |
| detect dead worker | `lease_until` + heartbeat column | activity heartbeat timeout |
| wait for human | poll a status column | `Workflow.await` + signal |
| inspect history | custom tables/UI | Temporal UI and history API |
| infrastructure | existing database | Temporal server/cluster |
| code you own | run store, leases, recovery, replay, SSE log | workflow + activities |

The ledger is still a valid lightweight option. Temporal is chosen here because the requirement is durable execution, not just durable records.

---

## 5. Scope

### In scope

- A durable `agent` run exposed by new endpoints.
- Planner, research/analysis steps, writer, critic, revisions, and memory write as Temporal activities.
- A queryable run view with current and last completed step.
- Optional plan approval before execution, surviving a restart.
- A repeatable crash demo.
- Workflow tests, replay tests, and a run-detail UI.
- Metrics and traces for workflow, activities, model calls, and tools.

### Out of scope for the POC

- Token-by-token streaming through Temporal.
- Multi-region Temporal, production sizing, or capacity planning.
- Migrating the existing `/api/agent/run` synchronous endpoint or the `chat` module.
- Replacing the `bank-agent` job scheduler.
- Authentication/authorization changes.
- A general workflow authoring UI.

### Explicit non-goal

Temporal does **not** make a single activity resumable from its middle. If a step is one activity, a crash mid-step retries that whole step. Per-tool-call durability requires `TemporalChatClient`, which is covered as an optional phase.

---

## 6. Target architecture

```text
Client / UI
   │
   ├─ POST /api/agent/runs
   │     └─ AgentRunService.start()
   │          └─ WorkflowClient.start(workflowId = runId)
   │
   ├─ GET /api/agent/runs/{runId}
   │     └─ AgentRunWorkflow.view()          @QueryMethod
   │
   ├─ POST /api/agent/runs/{runId}/approve
   │     └─ AgentRunWorkflow.approvePlan()   @SignalMethod
   │
   └─ GET /api/agent/runs/{runId}/events    optional SSE progress

Temporal worker (agent-run task queue)
   │
   └─ AgentRunWorkflowImpl
        ├─ prepareActivity      load memory, build goal, normalize plan
        ├─ planActivity         PlannerAgent
        ├─ [WAITING_APPROVAL]   Workflow.await(signal)
        ├─ stepActivity 1..n    researcher / analyst / writer
        ├─ writeActivity        WriterAgent
        ├─ reviewActivity       CriticAgent
        ├─ reviseActivity       WriterAgent (max 2 revisions)
        └─ rememberActivity     append to chat memory

Spring AI
   └─ ChatClient / tools / RAG run inside activities, unchanged
```

The workflow is the orchestrator. Activities are ordinary Spring beans and can use the existing agents, tools, MCP clients, vector store, and chat memory.

---

## 7. Module and file layout

All paths are under `/Users/ravening/Documents/spring-ai`.

### New files in `agent`

```text
agent/src/main/java/com/example/agent/temporal/
  AgentRunWorkflow.java
  AgentRunWorkflowImpl.java
  AgentRunActivities.java
  AgentRunActivitiesImpl.java
  AgentRunService.java
  AgentRunController.java
  AgentRunInput.java
  AgentRunView.java
  StepView.java
  RunStatus.java
  StepStatus.java
  PreparedRun.java
  StepExecution.java
  StepOutcome.java
  DraftOutcome.java
  ReviewOutcome.java
  WriteRequest.java
  ReviewRequest.java
  ReviseRequest.java
  RememberRequest.java
  StepState.java
  AgentRunSupport.java
  AgentStepExecutor.java
  RunProgressPublisher.java          optional SSE
```

### Modified files in `agent`

```text
agent/pom.xml
  + temporal-spring-boot-starter
  + temporal-sdk (optional; starter already pulls it)
  + spring-boot-starter-actuator
  + spring-boot-starter-opentelemetry
  + micrometer-registry-prometheus (runtime)

agent/src/main/resources/application.yml
  + spring.temporal.*
  + management.*
  + agent.temporal.demo.step-delay

agent/src/main/java/com/example/agent/agent/AgentOrchestrator.java
  dispatch() delegates to AgentStepExecutor
  buildGoal()/remember() delegate to AgentRunSupport

agent/src/main/java/com/example/agent/agent/StepResult.java
  make public for Temporal payloads

agent/src/main/java/com/example/agent/agent/Review.java
  already public; no change expected
```

### New files in `bank-agent` (phase 3)

```text
bank-agent/src/main/java/com/example/bank/temporal/
  TransferWorkflow.java
  TransferWorkflowImpl.java
  TransferActivities.java
  TransferActivitiesImpl.java
  TransferRequest.java
  TransferView.java
  TransferReceipt.java
  TransferRunService.java
  TransferRunController.java
```

### New tests

```text
agent/src/test/java/com/example/agent/temporal/
  AgentRunWorkflowTest.java
  AgentRunWorkflowReplayTest.java
  AgentRunControllerTest.java

bank-agent/src/test/java/com/example/bank/temporal/
  TransferWorkflowTest.java
```

---

## 8. Dependencies

### `agent/pom.xml`

```xml
<dependencies>
    <!-- existing dependencies stay as they are -->

    <dependency>
        <groupId>io.temporal</groupId>
        <artifactId>temporal-spring-boot-starter</artifactId>
        <version>${temporal.version}</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-opentelemetry</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

`temporal-spring-boot-starter` already brings `temporal-sdk` and `temporal-testing` transitively. Add `temporal-testing` explicitly with `test` scope only if Maven resolves it as compile and you want to tighten the scope.

Do **not** add `io.temporal:spring-ai` in phase 0. Add it only for the optional durable tool-loop demo:

```xml
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>spring-ai</artifactId>
    <version>${temporal-spring-ai.version}</version>
</dependency>
```

### Why not the old Spring AI Azure starter

The repository uses `spring-ai-starter-model-openai` with `spring.ai.openai.microsoft-foundry: true`. Keep that. The old `spring-ai-starter-model-azure-openai` artifact is not part of the 2.0.2 BOM.

---

## 9. Configuration

### `application.yml`

```yaml
spring:
  temporal:
    connection:
      target: ${TEMPORAL_TARGET:127.0.0.1:7233}
    namespace: ${TEMPORAL_NAMESPACE:default}
    workers:
      - task-queue: agent-run
        workflow-classes:
          - com.example.agent.temporal.AgentRunWorkflowImpl
        activity-beans:
          - agentRunActivities

agent:
  temporal:
    demo:
      # Used only by the crash demo. 0 disables the artificial delay.
      step-delay: ${AGENT_TEMPORAL_STEP_DELAY:0s}
    run:
      execution-timeout: 2h
      step-start-to-close: 5m
      heartbeat-timeout: 15s
      max-attempts: 3
```

### Worker registration choices

Two valid approaches:

1. **Explicit worker config** (recommended for the POC): list `workflow-classes` and `activity-beans` under `spring.temporal.workers`. Deterministic and easy to debug.
2. **Annotation auto-discovery**: annotate the workflow impl with `@WorkflowImpl(workers = "agent-run")` and the activity impl with `@ActivityImpl(workers = "agent-run")`, then enable `spring.temporal.workers-auto-discovery`.

If explicit config is used, `AgentRunWorkflowImpl` still carries `@WorkflowImpl(workers = "agent-run")` for readability, but the explicit list is authoritative. `AgentRunActivitiesImpl` is a normal Spring bean named `agentRunActivities`; `@ActivityImpl` is optional.

### Local Temporal

```bash
temporal server start-dev
# frontend: 127.0.0.1:7233
# UI:       http://localhost:8233
```

Production options:

- Temporal Cloud (managed; recommended for a real deployment).
- Self-hosted Temporal with PostgreSQL or Cassandra persistence and a visibility store.
- Do not use the dev server for anything beyond the demo.

### Actuator and OTLP

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  tracing:
    sampling:
      probability: 0.2
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}

spring:
  ai:
    chat:
      observations:
        log-prompt: false
        log-completion: false
    tools:
      observations:
        include-content: false
```

Keep prompt/completion/tool content logging off in any environment with real banking data. Turn it on only in a local demo with synthetic data.

---

## 10. Payload contracts

All payload types are public records with simple fields. Temporal serializes them as JSON.

```java
public record AgentRunInput(
        String runId,
        String conversationId,
        String userId,
        String task,
        AgentPlan approvedPlan,
        boolean requireApproval) {
}

public record PreparedRun(
        String goal,
        AgentPlan plan) {
}

public record StepExecution(
        String runId,
        String conversationId,
        int index,
        PlanStep step,
        List<StepResult> priorResults,
        String goal) {
}

public record StepOutcome(
        String output,
        List<AgentEvent> events,
        List<ToolCallRecord> toolCalls,
        long durationMs,
        int attempt) {
}

public record DraftOutcome(
        String draft,
        List<AgentEvent> events) {
}

public record ReviewOutcome(
        Review review,
        List<AgentEvent> events) {
}

public record WriteRequest(
        String goal,
        List<StepResult> priorResults) {
}

public record ReviewRequest(
        String goal,
        String draft,
        List<StepResult> priorResults) {
}

public record ReviseRequest(
        String goal,
        String draft,
        String feedback,
        List<StepResult> priorResults) {
}

public record RememberRequest(
        String conversationId,
        String task,
        String answer,
        String runId) {
}

public record AgentRunView(
        String runId,
        RunStatus status,
        int currentStep,
        Integer lastCompletedStep,
        List<StepView> steps,
        String answer,
        String error) {
}

public record StepView(
        int index,
        String agent,
        String instruction,
        StepStatus status,
        int attempt,
        long startedAt,
        Long finishedAt,
        String error) {
}
```

Enums:

```java
public enum RunStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED
}

public enum StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    NEEDS_RECONCILIATION
}
```

`AgentEvent`, `ToolCallRecord`, `AgentPlan`, `PlanStep`, and `Review` come from the existing code. `StepResult` is currently package-private and must become `public` to appear in activity payloads, or be replaced by a Temporal-specific record.

Temporal's default Jackson 2 converter registers `JavaTimeModule`, so `Instant` fields in `AgentEvent` and `ToolCallRecord` serialize correctly. If the optional `io.temporal:spring-ai` module switches the default converter to Jackson 3, verify with one workflow test; if needed, configure a `DataConverter` or use epoch millis in payloads.

---

## 11. Workflow design

### Interface

```java
@WorkflowInterface
public interface AgentRunWorkflow {

    @WorkflowMethod
    String run(AgentRunInput input);

    @QueryMethod
    AgentRunView view();

    @SignalMethod
    void approvePlan(boolean approved);
}
```

### Implementation

Key points:

- The class is instantiated by Temporal, not by Spring. Do not annotate it with `@Component`.
- It does not inject Spring beans. It creates activity stubs.
- All state below is rebuilt deterministically during replay.
- Use `Workflow.currentTimeMillis()` and `Workflow.newTimer()`, never `Instant.now()` or `Thread.sleep`.

```java
@WorkflowImpl(workers = "agent-run")
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private static final int MAX_REVISIONS = 2;

    private final AgentRunActivities activities = Workflow.newActivityStub(
            AgentRunActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setMaximumAttempts(3)
                            .setDoNotRetry(IllegalArgumentException.class.getName())
                            .build())
                    .build());

    private final List<StepState> steps = new ArrayList<>();
    private RunStatus status = RunStatus.PENDING;
    private Boolean approval;
    private String answer;
    private String error;

    @Override
    public String run(AgentRunInput input) {
        try {
            status = RunStatus.RUNNING;

            PreparedRun prepared = activities.prepare(input);
            buildStepStates(prepared.plan());

            if (input.requireApproval() && input.approvedPlan() == null) {
                status = RunStatus.WAITING_APPROVAL;
                boolean answered = Workflow.await(
                        Duration.ofHours(24), () -> approval != null);
                if (!answered || !Boolean.TRUE.equals(approval)) {
                    status = RunStatus.CANCELLED;
                    return null;
                }
                status = RunStatus.RUNNING;
            }

            List<StepResult> prior = new ArrayList<>();
            List<PlanStep> planSteps = prepared.plan().steps();

            for (int i = 0; i < planSteps.size(); i++) {
                PlanStep step = planSteps.get(i);
                int attempt = steps.get(i).attempt() + 1;

                steps.get(i).markRunning(Workflow.currentTimeMillis(), attempt);

                StepOutcome outcome = activities.executeStep(new StepExecution(
                        input.runId(),
                        input.conversationId(),
                        i,
                        step,
                        List.copyOf(prior),
                        prepared.goal()));

                steps.get(i).markCompleted(outcome, Workflow.currentTimeMillis());
                prior.add(new StepResult(step.agent(), step.instruction(), outcome.output()));
            }

            DraftOutcome draft = activities.write(
                    new WriteRequest(prepared.goal(), List.copyOf(prior)));

            ReviewOutcome review = activities.review(
                    new ReviewRequest(prepared.goal(), draft.draft(), List.copyOf(prior)));

            int revisions = 0;
            while (!review.review().approved() && revisions < MAX_REVISIONS) {
                draft = activities.revise(new ReviseRequest(
                        prepared.goal(),
                        draft.draft(),
                        review.review().feedback(),
                        List.copyOf(prior)));

                review = activities.review(new ReviewRequest(
                        prepared.goal(), draft.draft(), List.copyOf(prior)));

                revisions++;
            }

            activities.remember(new RememberRequest(
                    input.conversationId(), input.task(), draft.draft(), input.runId()));

            answer = draft.draft();
            status = RunStatus.COMPLETED;
            return answer;
        }
        catch (Exception ex) {
            error = ex.getMessage();
            status = RunStatus.FAILED;
            throw ex;
        }
    }

    @Override
    public AgentRunView view() {
        return new AgentRunView(
                Workflow.getInfo().getWorkflowId(),
                status,
                currentStep(),
                lastCompletedStep(),
                steps.stream().map(StepState::view).toList(),
                answer,
                error);
    }

    @Override
    public void approvePlan(boolean approved) {
        this.approval = approved;
    }

    private int currentStep() {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).status() == StepStatus.RUNNING) {
                return i + 1;
            }
        }
        return steps.size();
    }

    private Integer lastCompletedStep() {
        Integer last = null;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).status() == StepStatus.COMPLETED) {
                last = i + 1;
            }
        }
        return last;
    }
}
```

### Determinism rules

- No direct HTTP, database, file, `ChatClient`, `System.currentTimeMillis()`, `Instant.now()`, `UUID.randomUUID()`, `new Random()`, or `Thread.sleep` in workflow code.
- Use `Workflow.currentTimeMillis()`, `Workflow.newRandom()`, `Workflow.newTimer()`.
- Log with `Workflow.getLogger(...)` if logs are needed inside the workflow; otherwise log from activities.
- Any change to workflow code after runs exist must be guarded with `Workflow.getVersion(...)` or a worker deployment version. For the POC, reset the workflow history when changing the workflow.
- Do not pass Spring beans, `ChatClient`, `Prompt`, `ChatResponse`, or other non-serializable objects through the workflow. Keep model interactions inside activities.

### Failure behavior

- Activity failures are retried according to `RetryOptions`.
- Validation errors (bad prompt, 4xx) should be thrown as non-retryable `ApplicationFailure` from the activity.
- If the workflow catches the failure, it records `FAILED` and rethrows. Temporal marks the workflow failed, and the query still exposes the step-level state for diagnostics.
- If querying a failed workflow is not acceptable in the chosen Temporal version, add a small read-model projection (see section 15) or catch the failure and return a failure result instead of rethrowing.

---

## 12. Activity design

### Interface

```java
@ActivityInterface
public interface AgentRunActivities {

    PreparedRun prepare(AgentRunInput input);

    StepOutcome executeStep(StepExecution execution);

    DraftOutcome write(WriteRequest request);

    ReviewOutcome review(ReviewRequest request);

    DraftOutcome revise(ReviseRequest request);

    void remember(RememberRequest request);
}
```

### Implementation responsibilities

| Activity | Existing code used | Timeout | Notes |
|---|---|---|---|
| `prepare` | `AgentRunSupport.buildGoal`, `PlannerAgent.plan`, `normalizePlan` | 2 min | returns `goal + plan`; if `approvedPlan` supplied, normalize and skip the planner |
| `executeStep` | `AgentStepExecutor.execute` | 5 min | researcher / analyst / writer dispatch; returns output + trace events + tool calls |
| `write` | `WriterAgent.synthesize` | 5 min | draft |
| `review` | `CriticAgent.review` | 5 min | structured `Review` |
| `revise` | `WriterAgent.revise` | 5 min | max 2 revisions enforced by the workflow |
| `remember` | `ChatMemory.add` | 30 s | idempotent by `runId` |

The implementation is a normal Spring bean:

```java
@Component("agentRunActivities")
public class AgentRunActivitiesImpl implements AgentRunActivities {

    private final PlannerAgent planner;
    private final AgentStepExecutor stepExecutor;
    private final WriterAgent writer;
    private final CriticAgent critic;
    private final ChatMemory chatMemory;
    private final AgentRunSupport support;

    // constructor injection

    @Override
    public PreparedRun prepare(AgentRunInput input) {
        AgentTrace trace = new AgentTrace(input.conversationId());
        String goal = support.buildGoal(input.conversationId(), input.task());

        AgentPlan plan = input.approvedPlan() != null
                ? support.normalizePlan(input.approvedPlan())
                : support.normalizePlan(planner.plan(goal, trace));

        return new PreparedRun(goal, plan);
    }

    @Override
    public StepOutcome executeStep(StepExecution execution) {
        AgentTrace trace = new AgentTrace(execution.conversationId());
        long start = System.nanoTime();
        String output = stepExecutor.execute(
                execution.step(),
                execution.priorResults(),
                trace,
                execution.goal());
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new StepOutcome(output, trace.events(), trace.toolCalls(), durationMs, 1);
    }

    // write/review/revise/remember follow the same shape
}
```

### Heartbeats

For activities that make several sequential calls, heartbeat between calls:

```java
Activity.getExecutionContext().heartbeat("researcher: calling model");
```

For a single long model call, the heartbeat cannot fire mid-call. Set `startToCloseTimeout` greater than the model timeout, or keep the heartbeat timeout greater than the longest single call.

### Retry policy

```java
RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(2))
        .setBackoffCoefficient(2.0)
        .setMaximumInterval(Duration.ofMinutes(1))
        .setMaximumAttempts(3)
        .setDoNotRetry(IllegalArgumentException.class.getName())
        .build();
```

For OpenAI/Azure rate limits:

- If the existing `ChatClient` handles retries, keep them but account for the total attempts.
- If `io.temporal:spring-ai` is used, set `spring.ai.openai.chat.max-retries=0` so each Temporal activity attempt makes one provider request. The integration maps provider `Retry-After` to Temporal retry delays.
- `insufficient_quota` is non-retryable; do not burn activity attempts on it.

---

## 13. Refactoring the existing `agent` orchestrator

The goal is to reuse behavior, not duplicate it.

### `AgentStepExecutor`

Extract the private `dispatch` method:

```java
@Service
public class AgentStepExecutor {

    private final ResearchAgent researcher;
    private final AnalystAgent analyst;
    private final WriterAgent writer;

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
```

`AgentOrchestrator` keeps its current behavior and delegates `dispatch` to this bean.

### `AgentRunSupport`

Extract the reusable helpers:

```java
@Service
public class AgentRunSupport {

    private final ChatMemory chatMemory;
    private final int maxPlanSteps;

    public String buildGoal(String conversationId, String task) { ... }

    public AgentPlan normalizePlan(AgentPlan plan) { ... }

    public void remember(String conversationId, String task, String answer) { ... }
}
```

`AgentOrchestrator` delegates to it. This keeps the synchronous path working unchanged.

### Chat memory idempotency

`ChatMemory.add` appends messages. If `rememberActivity` is retried after it actually wrote the messages, the user will see duplicates.

Options, in order of preference:

1. **Dedupe table**: create `agent_run_memory(run_id varchar primary key, created_at bigint)`. The activity inserts the `runId` first; if the insert fails with a duplicate key, skip `ChatMemory.add`. This is a tiny amount of code and is reliable.
2. **Metadata check**: if the JDBC chat-memory repository persists message metadata, check for `runId` before appending.
3. **Accept the risk for the POC**, document it, and add the dedupe table before the bank phase.

Use option 1 for the POC. It makes the “no duplicate assistant message” assertion testable.

---

## 14. Service and REST API

### `AgentRunService`

```java
@Service
public class AgentRunService {

    private final WorkflowClient workflowClient;

    public String start(AgentRunInput input, String runId) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(runId)
                .setTaskQueue("agent-run")
                .setWorkflowExecutionTimeout(Duration.ofHours(2))
                .setWorkflowRunTimeout(Duration.ofHours(2))
                .setWorkflowTaskTimeout(Duration.ofSeconds(30))
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();

        AgentRunWorkflow workflow = workflowClient.newWorkflowStub(
                AgentRunWorkflow.class, options);

        WorkflowClient.start(workflow::run, input);
        return runId;
    }

    public AgentRunView view(String runId) {
        return workflowClient.newWorkflowStub(AgentRunWorkflow.class, runId).view();
    }

    public void approve(String runId, boolean approved) {
        workflowClient.newWorkflowStub(AgentRunWorkflow.class, runId)
                .approvePlan(approved);
    }
}
```

Use a caller-supplied `runId` or generate one in the controller. A client-supplied `runId` plus `REJECT_DUPLICATE` makes `POST` idempotent. If the client retries the same request, the second start is rejected and the existing run can be returned.

### Endpoints

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/api/agent/runs` | `{task, conversationId, approvedPlan, requireApproval, runId?}` | `202 {runId, statusUrl}` |
| `GET` | `/api/agent/runs/{runId}` | — | `200 AgentRunView`, `404` if unknown |
| `POST` | `/api/agent/runs/{runId}/approve` | `{approved: true}` | `202`, `409` if not waiting |
| `POST` | `/api/agent/runs/{runId}/cancel` | — | `202` (optional) |
| `GET` | `/api/agent/runs/{runId}/events` | — | `text/event-stream` (optional) |

The existing `/api/agent/plan` and `/api/agent/run` remain unchanged. The durable path is additive; the existing UI keeps working.

### Error handling

- Unknown workflow: catch `WorkflowNotFoundException` → `404`.
- Duplicate start: catch `WorkflowExecutionAlreadyStarted` → `409` with the existing `runId`.
- Approval when the workflow is not waiting: signal is a no-op in Temporal; the controller can inspect the view first and return `409`.
- Query on a completed workflow: supported; return the final view.
- Query on a failed workflow: verify in the POC. If it is not reliable, add a projection table or return a synthesized view from `WorkflowStub.describe()` plus `fetchHistory()`.

### Optional SSE

Do **not** try to stream model tokens through Temporal. For progress:

- Poll `view()` every 500–1000 ms on the server and emit changed steps as SSE events.
- Or use a `RunProgressPublisher` invoked by activities. For a single instance this can be an in-memory sink; for multiple instances use Redis pub/sub or a database outbox.
- SSE is best-effort unless backed by a durable event log. Document that reconnect does not replay old events. If replay is required later, use the `agent_event` table from the ledger design.

The bank UI currently streams tokens. Keep that path on the existing Spring AI `ChatClient`; use Temporal only for runs that do not need token streaming.

---

## 15. State model and “last successful step”

Workflow-local `StepState`:

```java
final class StepState {

    private final int index;
    private final String agent;
    private final String instruction;
    private StepStatus status = StepStatus.PENDING;
    private int attempt;
    private long startedAt;
    private Long finishedAt;
    private String error;

    void markRunning(long now, int attempt) {
        this.status = StepStatus.RUNNING;
        this.attempt = attempt;
        this.startedAt = now;
    }

    void markCompleted(StepOutcome outcome, long now) {
        this.status = StepStatus.COMPLETED;
        this.finishedAt = now;
    }

    StepView view() {
        return new StepView(
                index, agent, instruction, status, attempt, startedAt, finishedAt, error);
    }
}
```

`lastCompletedStep` is the highest completed index, not simply `currentStep - 1`, because a parallel or skipped step may not be complete.

For parallel plan steps (phase 2 extension), use:

```java
List<Promise<StepOutcome>> promises = steps.stream()
        .map(step -> Async.function(activities::executeStep, execution))
        .toList();

Promise.allOf(promises).get();
```

Mark each step completed as its promise resolves, and expose the highest contiguous completed index if the ordering matters.

### Optional read-model projection

Temporal’s query is enough for `GET /runs/{id}`. For a run list or dashboard, use one of:

- Temporal visibility: `WorkflowClient.listExecutions(...)` filtered by workflow type/task queue.
- A small `agent_run_view` table updated by a `project` activity after each step. This is not required for durability and should not become the source of truth.

Keep the rule: **Temporal history is authoritative; any table is a read model.**

---

## 16. Crash demo

### Setup

```bash
# terminal 1
temporal server start-dev

# terminal 2
cd /Users/ravening/Documents/spring-ai/agent
AGENT_TEMPORAL_STEP_DELAY=15s ./mvnw spring-boot:run
```

### Steps

1. Submit a run:
   ```bash
   curl -s -X POST localhost:8090/api/agent/runs \
     -H 'Content-Type: application/json' \
     -d '{"task":"Compare the last three quarterly reports and write a summary","conversationId":"demo-1"}'
   ```
   Note the `runId`.

2. Watch the view:
   ```bash
   watch -n1 "curl -s localhost:8090/api/agent/runs/<runId> | jq"
   ```

3. When step 3 is `RUNNING`, kill the JVM:
   ```bash
   kill -9 <pid>
   ```

4. Open Temporal UI (`http://localhost:8233`) and inspect the workflow:
   - steps 1–2: one `ActivityTaskCompleted` each
   - step 3: `ActivityTaskStarted`, then `ActivityTaskTimedOut` or heartbeat timeout
   - workflow still `Running`

5. Restart the application:
   ```bash
   AGENT_TEMPORAL_STEP_DELAY=0 ./mvnw spring-boot:run
   ```

6. Query again:
   ```bash
   curl -s localhost:8090/api/agent/runs/<runId> | jq
   ```
   Expect `lastCompletedStep: 2`, step 3 `RUNNING` with `attempt: 2`, then eventually `COMPLETED`.

7. Verify history:
   - exactly one completion for steps 1–2
   - multiple attempts for step 3
   - exactly one workflow completion

### Timing knobs

- `startToCloseTimeout` decides how long Temporal waits before retrying a dead activity when heartbeats are not used.
- `heartbeatTimeout` detects a dead worker faster when the activity heartbeats between calls.
- Keep the demo delay shorter than the start-to-close timeout so the kill happens while the activity is alive.

### Expected user-facing answer

While the app is down, the API is down. The durable state is in Temporal:

```bash
temporal workflow show --workflow-id <runId>
```

After restart, `GET /runs/{runId}` returns the same step state and the run continues. That is the proof that the last successful step was not lost.

---

## 17. Idempotency and side effects

### The rule

Every activity that changes the world must be safe to run more than once.

### Key derivation

- `Activity.getInfo().getActivityId()` is stable across retries of the same activity task.
- For a transfer, use the activity ID directly or `runId + ":transfer:" + attemptGroup`.
- Do not derive the key from the model’s arguments alone; the model may produce different text on a retry.
- Do not use a random UUID generated inside the activity; a retry would generate a new key and duplicate the effect.

### `bank-agent` transfer specifics

Current state:

- `BankStore` has a unique `idempotency_key`.
- `TransferService` accepts an idempotency key and returns the original result for a repeated key.
- `TransferTools.transferMoney()` passes `null`.

Changes:

1. Change `TransferTools.transferMoney(...)` to accept an `idempotencyKey` argument. For the immediate chat path, derive a stable key from `conversationId + messageId` or a UUID stored with the request; for the workflow path, pass `Activity.getInfo().getActivityId()`.
2. Pass the key through `TransferService` to `BankStore`.
3. On a timeout or unknown outcome, do not retry blindly. Throw a non-retryable `UnknownOutcomeException`, mark the step `NEEDS_RECONCILIATION`, and run a `reconcile` activity that calls `BankStore.findByIdempotencyKey(...)`.
4. Notifications use the existing `dedupeKey`; do not emit a second notification when the transfer was replayed.

### What “exactly once” means here

- The activity may be attempted more than once.
- The database unique constraint ensures one transfer row.
- The returned result is the original result.
- The user sees one transfer, one notification, and one assistant message.

---

## 18. `bank-agent` phase 3

### Workflow

```java
@WorkflowInterface
public interface TransferWorkflow {

    @WorkflowMethod
    TransferReceipt run(TransferRequest request);

    @QueryMethod
    TransferView view();

    @SignalMethod
    void approve(boolean approved);
}
```

### Flow

```text
prepare/validate activity
   └─ read accounts, check limits, resolve recipients (read-only)
        │
        ▼
WAITING_APPROVAL
   └─ Workflow.await(signal)
        │
        ▼
executeTransfer activity
   └─ idempotency key = Activity.getInfo().getActivityId()
   └─ TransferService.execute(...)
        │
        ├─ success → notify activity (dedupeKey) → COMPLETED
        └─ unknown → NEEDS_RECONCILIATION → reconcile activity
```

### Why this is a good bank demo

- A crash while waiting for approval does not lose the request.
- A crash after the transfer commits but before the activity returns does not move money twice.
- The view distinguishes `NEEDS_RECONCILIATION` from a normal failure, so an operator can act.
- The existing `TransferService` and `BankStore` already support the idempotency contract; only the key plumbing changes.

### Do not migrate everything

Keep the existing `JobService` for cron/scheduled jobs. Temporal owns the agent run, not the entire bank scheduler. A later phase can replace the job claim/lease loop if desired, but it is not needed for this POC.

---

## 19. Optional `io.temporal:spring-ai` demo

Add this only after the core POC works. It demonstrates finer-grained durability: model calls and tool calls are Temporal activities.

```java
@WorkflowInterface
public interface DurableToolChatWorkflow {

    @WorkflowMethod
    String chat(String prompt);
}
```

```java
@WorkflowImpl(workers = "agent-run")
public class DurableToolChatWorkflowImpl implements DurableToolChatWorkflow {

    @Override
    public String chat(String prompt) {
        ActivityChatModel model = ActivityChatModel.forDefault();

        AddTool addTool = Workflow.newActivityStub(
                AddTool.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .build());

        ChatClient client = TemporalChatClient.builder(model)
                .defaultTools(addTool)
                .build();

        return client.prompt().user(prompt).call().content();
    }
}
```

```java
@ActivityInterface
public interface AddTool {

    @Tool(description = "Add two numbers")
    @ActivityMethod
    int add(int a, int b);
}
```

### What it proves

- The model call is an activity; a crash after it returns replays the response from history instead of calling the model again.
- Each tool call is an activity; a crash between tool calls replays completed tool results.
- Temporal UI shows the model and tool activities separately.

### Caveats

- No token streaming.
- `ToolContext` is unsupported. The bank tools that rely on `ContextInjectingToolCallback` cannot use this path without passing IDs explicitly.
- Disable provider retries: `spring.ai.openai.chat.max-retries=0`.
- Structured output conversion errors happen in workflow code. Catch `JacksonException` around `entity(...)`/`responseEntity(...)` or configure `setFailWorkflowExceptionTypes` so the workflow fails cleanly instead of retrying forever.
- Keep this demo separate from the main `AgentRunWorkflow`; it is a capability demo, not the production path.

---

## 20. Observability

### Layer 1 — Temporal UI and history

Temporal UI is the step-level trace:

- workflow status, current activity, and pending activities
- every activity attempt with input/result
- timeouts, retries, and worker identity
- workflow task failures with stack traces
- query results and signal history

Use it during the crash demo and for post-mortems. `temporal workflow show` and `temporal workflow describe` are the CLI equivalents.

### Layer 2 — metrics

Dependencies are in section 8. The Temporal starter’s `MetricsScopeAutoConfiguration` bridges Temporal SDK metrics into Micrometer.

Metrics to watch:

- workflow completed / failed / timed out
- activity execution failed / retried
- activity schedule-to-start latency
- workflow task execution latency
- worker task slots available
- Spring AI `gen_ai.client.operation` and `gen_ai.client.token.usage`
- `spring.ai.tool` latency and errors

Application metrics to add:

- `agent.run.started`, `agent.run.completed`, `agent.run.failed`
- `agent.step.duration` tagged by `agent`, `status`
- `agent.step.retries`
- `agent.run.waiting_approval`
- `agent.run.stuck` (open workflow with no progress beyond a threshold)
- token/cost per run, agent, and model

Keep `runId` out of metric tags. Use traces and logs for high-cardinality identifiers.

### Layer 3 — distributed tracing

The starter’s `OpenTracingAutoConfiguration` creates a Temporal `Tracer` from the Boot OTel `OpenTelemetry` bean. Add the OTel starter and configure OTLP export.

Expected span tree:

```text
POST /api/agent/runs (http.server)
└─ StartWorkflow:AgentRunWorkflow
   └─ AgentRunWorkflow (workflow task)
      ├─ prepare
      ├─ plan
      │  └─ spring.ai.chat.client
      │     └─ gen_ai.client.operation
      ├─ step[1]
      │  ├─ spring.ai.chat.client
      │  └─ spring.ai.tool
      ├─ step[2]
      ├─ write
      └─ review
```

Add attributes:

- `run.id`
- `conversation.id` (hashed or internal only)
- `agent.name`
- `step.index`
- `step.attempt`
- `gen_ai.operation.name`, `gen_ai.request.model`, token usage (Spring AI already emits these)

### Logs

- Structured logging (`logging.structured.format.console=ecs` or JSON).
- MDC: `runId`, `conversationId`, `stepIndex`, `agent`.
- Log lifecycle at INFO, retries at WARN, failures at ERROR with the stack trace.
- Do not log prompts, completions, account numbers, or tool arguments in production.

### Alerts

- failed workflows > 0 in 5 minutes
- activity retry rate > 5%
- activity schedule-to-start p95 above SLO
- worker task slots exhausted
- open workflow with no progress for > 10 minutes
- token spend per run/agent above budget
- provider 429 rate above threshold
- `NEEDS_RECONCILIATION` count > 0 (bank)

### Azure export

- OTLP → OTel Collector → Azure Monitor exporter, or
- Application Insights Java agent with `APPLICATIONINSIGHTS_CONNECTION_STRING`, or
- `com.azure:azure-monitor-opentelemetry-exporter` in the application.

Use the GenAI semantic conventions (`gen_ai.*`) so Application Insights and Foundry understand model and tool spans. Keep prompt/completion content disabled; redact account numbers before export.

---

## 21. Testing strategy

### Workflow unit tests

Use `TestWorkflowEnvironment` and `TestWorkflowExtension`-style setup (or a manual environment).

| Test | Assertion |
|---|---|
| happy path | 3 steps, each activity invoked once, workflow returns the answer |
| step 2 fails twice then succeeds | step 1 invoked once; step 2 invoked 3 times; workflow completes |
| non-retryable validation failure | workflow fails; step 3 never invoked |
| approval signal | workflow waits; `view().status == WAITING_APPROVAL`; signal resumes; completes |
| approval rejected | workflow returns `CANCELLED`; no steps executed |
| duplicate approval signal | no error; one transition |
| query during step 3 | `lastCompletedStep == 2`; step 3 `RUNNING` |
| completion replay | assistant message written once; no duplicate |

### Replay test

Record a real history from the dev server, then:

```java
WorkflowReplayer.replayWorkflowExecution(
        history,
        AgentRunWorkflowImpl.class);
```

This catches accidental non-determinism (new I/O, changed loop order, changed activity sequence).

### Controller test

`@WebMvcTest(AgentRunController.class)` with a mocked `AgentRunService`:

- `POST` returns `202` and `Location`
- `GET` returns the view
- unknown run returns `404`
- duplicate start returns `409`
- approve returns `202` / `409`

### Bank transfer tests

- approval → transfer → notification → completed
- kill-after-commit simulation: activity throws after `TransferService.execute`; retry; assert one row for the idempotency key
- unknown outcome → `NEEDS_RECONCILIATION` → reconcile activity finds the prior result
- duplicate notification suppressed by `dedupeKey`

### Crash integration test

A scripted or manual run against `temporal server start-dev`:

```bash
./scripts/temporal-crash-demo.sh
```

The script can:

1. start the app,
2. submit a run,
3. wait for step 2 to complete,
4. `kill -9` the app,
5. restart,
6. poll until `COMPLETED`,
7. assert step attempts from the Temporal history,
8. assert one assistant message and, for bank, one transfer row.

Keep this out of the default `mvn test` run; it needs a Temporal server and takes longer.

---

## 22. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| No token streaming | bank SSE UI cannot stream tokens through Temporal | keep the existing streaming chat path; use Temporal for long-running runs and stream progress/status only |
| Activity-level granularity | a crash mid-step retries the whole step | accept for the POC; use `TemporalChatClient` for per-tool-call durability |
| `TemporalChatClient` lacks `ToolContext` | bank tools depend on injected IDs | keep coarse activities for bank; pass IDs explicitly if using the integration |
| At-least-once activity execution | duplicate transfers/messages/notifications | idempotency keys, unique constraints, dedupe tables, reconciliation |
| Workflow determinism | replay failures | no I/O/random/time in workflow; `Workflow.*` helpers; replay tests; versioning |
| Payload size (2 MB default) | large step outputs fail | store large artifacts in DB/blob and pass a reference; truncate trace details |
| History size (50k events) | long runs hit limits | `continue-as-new`; keep per-step payloads small |
| New infrastructure | Temporal server to operate | dev server for POC; Temporal Cloud or managed self-hosting for production |
| Public-preview `spring-ai` integration | API changes | core POC does not use it; isolate the optional demo; pin 0.1.1 |
| Query on failed workflow | status API may be limited | verify; add a read-model projection if needed |
| Jackson 2 vs Jackson 3 | payload serialization mismatch | Temporal default converter is Jackson 2 with JavaTime; test `Instant` payloads; configure a converter if the integration switches defaults |

---

## 23. Implementation phases

### Phase 0 — skeleton and compatibility (0.5 day)

- [ ] Add Temporal and Actuator/OTel dependencies.
- [ ] Add `spring.temporal` worker config.
- [ ] Start `temporal server start-dev`.
- [ ] Add a hello-world workflow/activity and call it from a test or a temporary endpoint.
- [ ] Confirm Boot 4.1.2 + starter 1.41.1 starts and registers the worker.
- [ ] Confirm Temporal UI shows the workflow.

Exit criteria: a workflow starts, executes one activity, and appears in Temporal UI.

### Phase 1 — durable agent run (2 days)

- [ ] Extract `AgentStepExecutor` and `AgentRunSupport`.
- [ ] Make `StepResult` public (or map to a payload record).
- [ ] Add payload records and enums.
- [ ] Implement `AgentRunWorkflow` + `AgentRunWorkflowImpl`.
- [ ] Implement `AgentRunActivities` + `AgentRunActivitiesImpl`.
- [ ] Add `AgentRunService` and `AgentRunController`.
- [ ] Add `agent_run_memory` dedupe table and wire `remember`.
- [ ] Add happy-path workflow test.

Exit criteria: `POST /api/agent/runs` starts a run; `GET` returns the step view; the run completes with the same answer as the synchronous path.

### Phase 2 — crash, approval, and UI (1.5 days)

- [ ] Add the demo delay property and heartbeat logic.
- [ ] Add `approvePlan` signal and `WAITING_APPROVAL` state.
- [ ] Add optional SSE progress (polling or publisher).
- [ ] Add a run-detail section to the UI: step timeline, last completed step, approve button.
- [ ] Add workflow tests for retry, approval, rejection, and query.
- [ ] Add replay test.
- [ ] Run the crash demo and record the expected history.

Exit criteria: kill -9 mid-step, restart, run completes without re-running completed steps; approval survives a restart.

### Phase 3 — bank transfer workflow (2–3 days)

- [ ] Add Temporal dependencies/config to `bank-agent`.
- [ ] Implement `validate` / `approve` / `executeTransfer` / `notify` / `reconcile` activities.
- [ ] Change `TransferTools.transferMoney` to accept and pass an idempotency key.
- [ ] Add `NEEDS_RECONCILIATION` handling.
- [ ] Add transfer workflow tests, including kill-after-commit.
- [ ] Add a bank run-detail UI (optional).

Exit criteria: a crash after the transfer commit produces exactly one transfer row and one notification; approval survives restart.

### Phase 4 — observability and polish (1 day)

- [ ] Enable Actuator, Prometheus, OTel export.
- [ ] Add `run.id`/`step.index`/`agent.name` span attributes and MDC.
- [ ] Build a dashboard: run success rate, step latency, retries, token usage, stuck runs.
- [ ] Add alerts.
- [ ] Document the demo runbook and the Temporal operational model.
- [ ] Optional: `io.temporal:spring-ai` durable tool-loop demo.

Exit criteria: a run can be followed end-to-end in Temporal UI, metrics, and traces; alerts fire on a failed workflow.

---

## 24. Acceptance criteria

The POC is complete when all of these are true:

1. A run started through `POST /api/agent/runs` executes planner, steps, writer, critic, and memory write.
2. Killing the JVM during step 3 and restarting produces a completed run.
3. Steps 1–2 are not re-executed; step 3 is retried and its attempts are visible.
4. `GET /api/agent/runs/{runId}` reports `lastCompletedStep` and per-step status while the run is open.
5. A run waiting for plan approval survives a restart and resumes on a later signal.
6. The existing synchronous `/api/agent/run` and `/api/agent/plan` endpoints still work.
7. `remember` does not create duplicate assistant messages on retry.
8. Temporal UI shows one completion per completed step and the retry attempts for the interrupted step.
9. Metrics and traces are visible in the configured backend.
10. Bank phase: a crash after the transfer commit produces exactly one transfer row and one notification.

---

## 25. Open decisions

1. **Module order**: this plan starts in `agent`. If the user prefers `bank-agent` first, swap phases 1 and 3 and keep the same architecture.
2. **`io.temporal:spring-ai`**: include the optional durable tool-loop demo in phase 4, or defer it entirely. The core POC does not need it.
3. **Temporal hosting**: local dev server for the demo; Temporal Cloud vs self-hosted for anything beyond the demo.
4. **Progress UI**: poll the query (simplest), SSE from an in-memory publisher (single instance), or a durable event table (multi-instance/replay).
5. **Chat-memory dedupe**: `agent_run_memory` table (recommended) vs accepting the retry risk for the POC.

---

## 26. References

- Temporal Java SDK: https://docs.temporal.io/develop/java
- Temporal Spring Boot integration: https://docs.temporal.io/develop/java/integrations/spring-boot-integration
- Temporal observability: https://docs.temporal.io/develop/java/platform/observability
- Temporal Spring AI integration: https://github.com/temporalio/ai-integrations/tree/main/java/spring-ai
- Temporal Spring Boot demo: https://github.com/temporalio/spring-boot-demo
- Spring AI observability: https://docs.spring.io/spring-ai/reference/observability/index.html
- Spring Boot observability: https://docs.spring.io/spring-boot/4.1/reference/actuator/observability.html

Repository files referenced:

- `agent/src/main/java/com/example/agent/agent/AgentOrchestrator.java`
- `agent/src/main/java/com/example/agent/api/AgentController.java`
- `agent/src/main/java/com/example/agent/agent/PlanStep.java`
- `agent/src/main/java/com/example/agent/agent/StepResult.java`
- `agent/src/main/java/com/example/agent/core/AgentTrace.java`
- `agent/src/main/java/com/example/agent/core/AgentEvent.java`
- `agent/src/main/java/com/example/agent/core/ToolCallRecord.java`
- `bank-agent/src/main/java/com/example/bank/bank/TransferService.java`
- `bank-agent/src/main/java/com/example/bank/bank/BankStore.java`
- `bank-agent/src/main/java/com/example/bank/tools/TransferTools.java`
- `bank-agent/src/main/java/com/example/bank/agent/RunRegistry.java`
- `bank-agent/src/main/java/com/example/bank/jobs/JobService.java`
