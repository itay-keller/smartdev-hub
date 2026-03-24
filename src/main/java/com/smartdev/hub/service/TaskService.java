package com.smartdev.hub.service;

import com.smartdev.hub.entity.ActivityLog;
import com.smartdev.hub.entity.Task;
import com.smartdev.hub.repository.ActivityLogRepository;
import com.smartdev.hub.repository.TaskRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ActivityLogRepository activityLogRepository;

    public TaskService(TaskRepository taskRepository, ActivityLogRepository activityLogRepository) {
        this.taskRepository = taskRepository;
        this.activityLogRepository = activityLogRepository;
    }

    public List<Task> getAllTasks() {
        return taskRepository.findAll();
    }

    public Optional<Task> getTaskById(Long id) {
        return taskRepository.findById(id);
    }

    public Task createTask(Task task) {
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        Task saved = taskRepository.save(task);
        activityLogRepository.save(new ActivityLog("TASK", saved.getId(), "CREATED",
                "Task created: " + saved.getTitle()));
        return saved;
    }

    public Optional<Task> updateTask(Long id, Task updates) {
        return taskRepository.findById(id).map(task -> {
            if (updates.getTitle() != null) task.setTitle(updates.getTitle());
            if (updates.getDescription() != null) task.setDescription(updates.getDescription());
            if (updates.getStatus() != null) task.setStatus(updates.getStatus());
            if (updates.getPriority() != null) task.setPriority(updates.getPriority());
            if (updates.getAssignee() != null) task.setAssignee(updates.getAssignee());
            task.setUpdatedAt(LocalDateTime.now());
            Task saved = taskRepository.save(task);
            activityLogRepository.save(new ActivityLog("TASK", saved.getId(), "UPDATED",
                    "Task updated: " + saved.getTitle()));
            return saved;
        });
    }

    public boolean deleteTask(Long id) {
        if (taskRepository.existsById(id)) {
            taskRepository.deleteById(id);
            activityLogRepository.save(new ActivityLog("TASK", id, "DELETED", "Task deleted"));
            return true;
        }
        return false;
    }

    public List<Task> getTasksByStatus(Task.Status status) {
        return taskRepository.findByStatus(status);
    }

    // NOTE: intentional bug left here for the agent to fix in Issue #2
    // When priority is null, this will throw a NullPointerException
    public List<Task> getTasksSortedByPriority() {
        return taskRepository.findAll().stream()
                .sorted(Comparator.comparing(t -> t.getPriority().ordinal()))
                .toList();
    }
}
