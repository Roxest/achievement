package com.pixelclub.achievement.model;

import java.util.Collections;
import java.util.List;

/**
 * Representation of one of the 16 achievement categories.
 */
public class AchievementCategory {
    private String id;
    private int index;
    private String name;
    private String description;
    private List<Achievement> achievements;

    public AchievementCategory() {}

    public String getId() { return id != null ? id : ""; }
    public int getIndex() { return index; }
    public String getName() { return name != null ? name : ""; }
    public String getDescription() { return description != null ? description : ""; }
    public List<Achievement> getAchievements() { return achievements != null ? achievements : Collections.emptyList(); }
    public int getTotalCount() { return getAchievements().size(); }
}
