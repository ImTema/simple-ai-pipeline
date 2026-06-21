package com.simplepipeline.declinemapper.model;

import org.springframework.ai.tool.annotation.ToolParam;

public record ProviderCode(
        @ToolParam(description = "The error code string (e.g. QP-001)") String code,
        @ToolParam(description = "Short name or title of the error") String name,
        @ToolParam(description = "Full explanation of what this error means") String description
) {}
