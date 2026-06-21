package com.simplepipeline.loganalyzer;

import com.simplepipeline.loganalyzer.model.IncidentAnalysis;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/log-analyzer")
public class LogAnalyzerController {

    private final LogAnalyzerService service;

    public LogAnalyzerController(LogAnalyzerService service) {
        this.service = service;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.TEXT_PLAIN_VALUE)
    public IncidentAnalysis analyze(@RequestBody String rawLogs) {
        return service.analyze(rawLogs);
    }
}
