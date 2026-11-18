package com.example.bank.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Structured output of the router agent: which domain agent handles the task. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteDecision(String intent, String note) {

    public RouteDecision {
        intent = intent == null ? "GENERAL" : intent;
        note = note == null ? "" : note;
    }
}
