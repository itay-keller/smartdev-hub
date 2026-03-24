package com.smartdev.hub.service;

import com.smartdev.hub.entity.ActivityLog;
import com.smartdev.hub.entity.FeatureFlag;
import com.smartdev.hub.repository.ActivityLogRepository;
import com.smartdev.hub.repository.FeatureFlagRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class FeatureFlagService {

    private final FeatureFlagRepository featureFlagRepository;
    private final ActivityLogRepository activityLogRepository;

    public FeatureFlagService(FeatureFlagRepository featureFlagRepository,
                               ActivityLogRepository activityLogRepository) {
        this.featureFlagRepository = featureFlagRepository;
        this.activityLogRepository = activityLogRepository;
    }

    public List<FeatureFlag> getAllFlags() {
        return featureFlagRepository.findAll();
    }

    public Optional<FeatureFlag> getFlagByName(String name) {
        return featureFlagRepository.findByName(name);
    }

    public FeatureFlag createFlag(FeatureFlag flag) {
        flag.setCreatedAt(LocalDateTime.now());
        flag.setUpdatedAt(LocalDateTime.now());
        FeatureFlag saved = featureFlagRepository.save(flag);
        activityLogRepository.save(new ActivityLog("FEATURE_FLAG", saved.getId(), "CREATED",
                "Flag created: " + saved.getName() + " (enabled=" + saved.isEnabled() + ")"));
        return saved;
    }

    public Optional<FeatureFlag> toggleFlag(String name) {
        return featureFlagRepository.findByName(name).map(flag -> {
            flag.setEnabled(!flag.isEnabled());
            flag.setUpdatedAt(LocalDateTime.now());
            FeatureFlag saved = featureFlagRepository.save(flag);
            activityLogRepository.save(new ActivityLog("FEATURE_FLAG", saved.getId(), "TOGGLED",
                    "Flag " + saved.getName() + " toggled to " + saved.isEnabled()));
            return saved;
        });
    }
}
