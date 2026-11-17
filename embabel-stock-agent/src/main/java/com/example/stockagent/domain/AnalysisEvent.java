package com.example.stockagent.domain;

/** SSE event contract shared by the Embabel agents and the web layer. */
public record AnalysisEvent(
        String type,
        String stage,
        String text,
        Object data,
        long timestamp
) {
    public static AnalysisEvent stage(String stage, String text) {
        return new AnalysisEvent("stage", stage, text, null, System.currentTimeMillis());
    }

    public static AnalysisEvent tool(String stage, String text) {
        return new AnalysisEvent("tool_call", stage, text, null, System.currentTimeMillis());
    }

    public static AnalysisEvent token(String stage, String text) {
        return new AnalysisEvent("token", stage, text, null, System.currentTimeMillis());
    }

    public static AnalysisEvent report(String stage, Object data) {
        return new AnalysisEvent("report", stage, "", data, System.currentTimeMillis());
    }

    public static AnalysisEvent error(String stage, String text) {
        return new AnalysisEvent("error", stage, text, null, System.currentTimeMillis());
    }

    public static AnalysisEvent done() {
        return new AnalysisEvent("done", "complete", "", null, System.currentTimeMillis());
    }
}
