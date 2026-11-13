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
    private String name_th;
    private String description;
    private String description_th;
    private List<Achievement> achievements;

    public AchievementCategory() {}

    public String getId() { return id != null ? id : ""; }
    public int getIndex() { return index; }
    public String getName() { return name != null ? name : ""; }
    public String getName(boolean isThai) { return (isThai && name_th != null && !name_th.isEmpty()) ? name_th : getName(); }
    public String getNameTh() { return name_th != null ? name_th : ""; }
    public String getDescription() { return description != null ? description : ""; }
    public String getDescription(boolean isThai) { return (isThai && description_th != null && !description_th.isEmpty()) ? description_th : getDescription(); }
    public String getDescriptionTh() { return description_th != null ? description_th : ""; }
    public List<Achievement> getAchievements() { return achievements != null ? achievements : Collections.emptyList(); }
    public int getTotalCount() { return getAchievements().size(); }
}
