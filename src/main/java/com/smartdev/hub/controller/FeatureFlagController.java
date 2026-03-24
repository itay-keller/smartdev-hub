package com.smartdev.hub.controller;

import com.smartdev.hub.entity.FeatureFlag;
import com.smartdev.hub.service.FeatureFlagService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/flags")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @GetMapping
    public List<FeatureFlag> getAllFlags() {
        return featureFlagService.getAllFlags();
    }

    @GetMapping("/{name}")
    public ResponseEntity<FeatureFlag> getFlag(@PathVariable String name) {
        return featureFlagService.getFlagByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FeatureFlag> createFlag(@Valid @RequestBody FeatureFlag flag) {
        return ResponseEntity.ok(featureFlagService.createFlag(flag));
    }

    @PostMapping("/{name}/toggle")
    public ResponseEntity<FeatureFlag> toggleFlag(@PathVariable String name) {
        return featureFlagService.toggleFlag(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
