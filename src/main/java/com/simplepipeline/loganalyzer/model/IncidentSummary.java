package com.simplepipeline.loganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public record IncidentSummary(
        @ToolParam(description = "Human-readable description of the incident") String description,
        @JsonProperty("affected_adapters") @ToolParam(description = "Names of affected payment adapters") List<String> affectedAdapters,
        @JsonProperty("affected_order_types") @ToolParam(description = "Order types impacted by the incident") List<String> affectedOrderTypes,
        @JsonProperty("fault_layer") @ToolParam(description = "Architectural layer where the fault originates") FaultLayer faultLayer,
        @ToolParam(description = "Incident severity level") Severity severity,
        @JsonProperty("severity_reasoning") @ToolParam(description = "Explanation of the severity assessment") String severityReasoning,
        @JsonProperty("blast_radius") @ToolParam(description = "Scope of impact across the platform") BlastRadius blastRadius
) {}
