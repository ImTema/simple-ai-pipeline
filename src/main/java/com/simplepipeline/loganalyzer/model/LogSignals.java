package com.simplepipeline.loganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record LogSignals(
        @JsonProperty("incident_id") String incidentId,
        @JsonProperty("error_types") List<String> errorTypes,
        @JsonProperty("affected_components") List<String> affectedComponents,
        List<String> timestamps
) {}
