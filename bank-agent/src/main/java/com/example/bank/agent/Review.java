package com.example.bank.agent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Structured verdict from the critic agent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Review(boolean approved, String summary, List<String> issues) {

    public Review {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public String feedback() {
        if (!issues.isEmpty()) {
            return String.join("\n", issues.stream().map(issue -> "- " + issue).toList());
        }
        return summary == null ? "No details were provided." : summary;
    }
}
