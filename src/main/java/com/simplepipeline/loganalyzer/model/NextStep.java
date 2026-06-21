package com.simplepipeline.loganalyzer.model;

import org.springframework.ai.tool.annotation.ToolParam;

public record NextStep(
        @ToolParam(description = "Description of the investigation action") String action,
        @ToolParam(description = "Infrastructure tool to use for this step") Tool tool,
        @ToolParam(description = "Specific query, dashboard, or command to execute") String detail
) {}
