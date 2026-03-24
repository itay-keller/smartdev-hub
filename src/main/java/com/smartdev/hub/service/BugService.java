package com.smartdev.hub.service;

import com.smartdev.hub.entity.ActivityLog;
import com.smartdev.hub.entity.Bug;
import com.smartdev.hub.repository.ActivityLogRepository;
import com.smartdev.hub.repository.BugRepository;
import com.smartdev.hub.repository.TaskRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BugService {

    private final BugRepository bugRepository;
    private final TaskRepository taskRepository;
    private final ActivityLogRepository activityLogRepository;

    public BugService(BugRepository bugRepository, TaskRepository taskRepository,
                      ActivityLogRepository activityLogRepository) {
        this.bugRepository = bugRepository;
        this.taskRepository = taskRepository;
        this.activityLogRepository = activityLogRepository;
    }

    public List<Bug> getAllBugs() {
        return bugRepository.findAll();
    }

    public Optional<Bug> getBugById(Long id) {
        return bugRepository.findById(id);
    }

    public Bug createBug(Bug bug, Long taskId) {
        if (taskId != null) {
            taskRepository.findById(taskId).ifPresent(bug::setRelatedTask);
        }
        bug.setCreatedAt(LocalDateTime.now());
        bug.setUpdatedAt(LocalDateTime.now());
        Bug saved = bugRepository.save(bug);
        activityLogRepository.save(new ActivityLog("BUG", saved.getId(), "CREATED",
                "Bug reported: " + saved.getTitle() + " [" + saved.getSeverity() + "]"));
        return saved;
    }

    public Optional<Bug> updateStatus(Long id, Bug.Status newStatus) {
        return bugRepository.findById(id).map(bug -> {
            String oldStatus = bug.getStatus().name();
            bug.setStatus(newStatus);
            bug.setUpdatedAt(LocalDateTime.now());
            Bug saved = bugRepository.save(bug);
            activityLogRepository.save(new ActivityLog("BUG", saved.getId(), "STATUS_CHANGED",
                    "Status changed from " + oldStatus + " to " + newStatus));
            return saved;
        });
    }

    public List<Bug> getBugsBySeverity(Bug.Severity severity) {
        return bugRepository.findBySeverity(severity);
    }

    public List<Bug> getBugsForTask(Long taskId) {
        return bugRepository.findByRelatedTaskId(taskId);
    }
}
