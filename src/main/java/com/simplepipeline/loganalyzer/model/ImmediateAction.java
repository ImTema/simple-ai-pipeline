package com.simplepipeline.loganalyzer.model;

import org.springframework.ai.tool.annotation.ToolParam;

public record ImmediateAction(
        @ToolParam(description = "Mitigation action to take immediately") String action,
        @ToolParam(description = "Risk level of performing this action") Risk risk,
        @ToolParam(description = "Why this action is recommended and what to watch for") String reasoning
) {}
