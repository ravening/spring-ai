package com.example.agent.temporal;

import java.util.List;

import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentRunController.class)
class AgentRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentRunService runService;

    @Test
    void startReturns202WithLocation() throws Exception {
        when(runService.start(any(AgentRunInput.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        mockMvc.perform(post("/api/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"Compare the reports\",\"conversationId\":\"demo-1\",\"runId\":\"run-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/agent/runs/run-1"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.statusUrl").value("/api/agent/runs/run-1"));
    }

    @Test
    void startWithoutTaskIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"demo-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateStartIsConflict() throws Exception {
        when(runService.start(any(AgentRunInput.class), anyString()))
                .thenThrow(WorkflowExecutionAlreadyStarted.class);

        mockMvc.perform(post("/api/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"t\",\"runId\":\"run-1\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void viewReturnsRunView() throws Exception {
        AgentRunView view = new AgentRunView("run-1", RunStatus.RUNNING, 2, 1,
                List.of(
                        new StepView(0, "RESEARCHER", "find facts", StepStatus.COMPLETED, 1, 1L, 2L, null),
                        new StepView(1, "ANALYST", "compute total", StepStatus.RUNNING, 1, 3L, null, null)),
                null, null);
        when(runService.view("run-1")).thenReturn(view);

        mockMvc.perform(get("/api/agent/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentStep").value(2))
                .andExpect(jsonPath("$.lastCompletedStep").value(1))
                .andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[1].status").value("RUNNING"));
    }

    @Test
    void unknownRunIsNotFound() throws Exception {
        when(runService.view("nope")).thenThrow(WorkflowNotFoundException.class);

        mockMvc.perform(get("/api/agent/runs/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void approveWaitingRunIsAccepted() throws Exception {
        when(runService.view("run-1")).thenReturn(
                new AgentRunView("run-1", RunStatus.WAITING_APPROVAL, 0, null, List.of(), null, null));

        mockMvc.perform(post("/api/agent/runs/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isAccepted());

        verify(runService).approve("run-1", true);
    }

    @Test
    void approveNonWaitingRunIsConflict() throws Exception {
        when(runService.view("run-1")).thenReturn(
                new AgentRunView("run-1", RunStatus.RUNNING, 1, 0, List.of(), null, null));

        mockMvc.perform(post("/api/agent/runs/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isConflict());

        verify(runService, never()).approve(anyString(), anyBoolean());
    }
}
