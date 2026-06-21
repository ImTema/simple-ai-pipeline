package com.simplepipeline.declinemapper;

import com.simplepipeline.declinemapper.model.MappingResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/decline-mapper")
public class DeclineMapperController {

    private final DeclineMapperService service;

    public DeclineMapperController(DeclineMapperService service) {
        this.service = service;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.TEXT_PLAIN_VALUE)
    public MappingResult analyze(@RequestBody String rawDoc) {
        return service.analyze(rawDoc);
    }
}
