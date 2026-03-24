package com.smartdev.hub.controller;

import com.smartdev.hub.entity.Bug;
import com.smartdev.hub.service.BugService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/bugs")
public class BugController {

    private final BugService bugService;

    public BugController(BugService bugService) {
        this.bugService = bugService;
    }

    @GetMapping
    public List<Bug> getAllBugs(@RequestParam(required = false) Bug.Severity severity) {
        if (severity != null) return bugService.getBugsBySeverity(severity);
        return bugService.getAllBugs();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Bug> getBug(@PathVariable Long id) {
        return bugService.getBugById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Bug> createBug(
            @Valid @RequestBody Bug bug,
            @RequestParam(required = false) Long taskId) {
        return ResponseEntity.ok(bugService.createBug(bug, taskId));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Bug> updateStatus(
            @PathVariable Long id,
            @RequestParam Bug.Status status) {
        return bugService.updateStatus(id, status)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/task/{taskId}")
    public List<Bug> getBugsForTask(@PathVariable Long taskId) {
        return bugService.getBugsForTask(taskId);
    }
}
