package com.simplepipeline.declinemapper.model;

import java.util.List;

public record MappingResult(
        String provider,
        String version,
        List<CodeMapping> mappings,
        MappingSummary summary
) {}
