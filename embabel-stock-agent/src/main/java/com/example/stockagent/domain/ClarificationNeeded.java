package com.example.stockagent.domain;

/** Returned when the user's message does not contain a usable ticker symbol. */
public record ClarificationNeeded(String message) {
}
