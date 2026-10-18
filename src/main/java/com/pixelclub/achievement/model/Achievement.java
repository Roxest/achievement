package com.pixelclub.achievement.model;

import java.util.Collections;
import java.util.List;

/**
 * Immutable definition of an achievement from the data registry.
 */
public class Achievement {
    private String id;
    private String category_id;
    private String category_name;
    private String section;
    private String name;
    private String description;
    private int target;
    private String trigger_bucket;
    private String trigger_type;
    private List<AchievementReward> rewards;
    private String reward_display;

    public Achievement() {}

    public String getId() { return id != null ? id : ""; }
    public String getCategoryId() { return category_id != null ? category_id : ""; }
    public String getCategoryName() { return category_name != null ? category_name : ""; }
    public String getSection() { return section != null ? section : ""; }
    public String getName() { return name != null ? name : ""; }
    public String getDescription() { return description != null ? description : ""; }
    public int getTarget() { return Math.max(1, target); }
    public String getTriggerBucket() { return trigger_bucket != null ? trigger_bucket : "BUILT_IN_HOOK"; }
    public String getTriggerType() { return trigger_type != null ? trigger_type : ""; }
    public List<AchievementReward> getRewards() { return rewards != null ? rewards : Collections.emptyList(); }
    public String getRewardDisplay() { return reward_display != null ? reward_display : ""; }

    public boolean isThresholdMet(int currentProgress) {
        return currentProgress >= getTarget();
    }
}
