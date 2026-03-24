package com.smartdev.hub.repository;

import com.smartdev.hub.entity.Bug;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BugRepository extends JpaRepository<Bug, Long> {
    List<Bug> findBySeverity(Bug.Severity severity);
    List<Bug> findByStatus(Bug.Status status);
    List<Bug> findByRelatedTaskId(Long taskId);
}
