package com.smartdev.hub.repository;

import com.smartdev.hub.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByStatus(Task.Status status);
    List<Task> findByAssignee(String assignee);
    List<Task> findByPriority(Task.Priority priority);
}
