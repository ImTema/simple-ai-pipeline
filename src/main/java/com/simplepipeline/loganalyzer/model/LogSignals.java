package com.simplepipeline.loganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public record LogSignals(
        @JsonProperty("incident_id") @ToolParam(description = "Incident identifier visible in the logs (e.g. INC-201), or null") String incidentId,
        @JsonProperty("error_types") @ToolParam(description = "Distinct error type strings found in the logs") List<String> errorTypes,
        @JsonProperty("affected_components") @ToolParam(description = "Component names (adapters, services, classes) mentioned in the logs") List<String> affectedComponents,
        @ToolParam(description = "First and last timestamps found in the logs") List<String> timestamps
) {}
