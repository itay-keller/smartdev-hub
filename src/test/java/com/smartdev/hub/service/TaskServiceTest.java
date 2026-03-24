package com.smartdev.hub.service;

import com.smartdev.hub.entity.Task;
import com.smartdev.hub.repository.ActivityLogRepository;
import com.smartdev.hub.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ActivityLogRepository activityLogRepository;

    @InjectMocks
    private TaskService taskService;

    private Task sampleTask;

    @BeforeEach
    void setUp() {
        sampleTask = new Task();
        sampleTask.setId(1L);
        sampleTask.setTitle("Implement login");
        sampleTask.setStatus(Task.Status.TODO);
        sampleTask.setPriority(Task.Priority.HIGH);
    }

    @Test
    void createTask_shouldSaveAndLogActivity() {
        when(taskRepository.save(any())).thenReturn(sampleTask);

        Task result = taskService.createTask(sampleTask);

        assertThat(result.getTitle()).isEqualTo("Implement login");
        verify(taskRepository).save(sampleTask);
        verify(activityLogRepository).save(any());
    }

    @Test
    void getTaskById_shouldReturnTask_whenExists() {
        when(taskRepository.findById(1L)).thenReturn(Optional.of(sampleTask));

        Optional<Task> result = taskService.getTaskById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo("Implement login");
    }

    @Test
    void getTaskById_shouldReturnEmpty_whenNotExists() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<Task> result = taskService.getTaskById(99L);

        assertThat(result).isEmpty();
    }

    @Test
    void deleteTask_shouldReturnTrue_whenExists() {
        when(taskRepository.existsById(1L)).thenReturn(true);

        boolean result = taskService.deleteTask(1L);

        assertThat(result).isTrue();
        verify(taskRepository).deleteById(1L);
    }

    @Test
    void deleteTask_shouldReturnFalse_whenNotExists() {
        when(taskRepository.existsById(99L)).thenReturn(false);

        boolean result = taskService.deleteTask(99L);

        assertThat(result).isFalse();
        verify(taskRepository, never()).deleteById(any());
    }

    // This test exposes the known NPE bug — Issue #2 for the agent to fix
    @Test
    void getTasksSortedByPriority_shouldThrow_whenPriorityIsNull() {
        Task taskWithNullPriority = new Task();
        taskWithNullPriority.setTitle("No priority task");

        when(taskRepository.findAll()).thenReturn(List.of(taskWithNullPriority, sampleTask));

        assertThatThrownBy(() -> taskService.getTasksSortedByPriority())
                .isInstanceOf(NullPointerException.class);
    }
}
