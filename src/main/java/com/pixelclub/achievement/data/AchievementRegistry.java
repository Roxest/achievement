package com.pixelclub.achievement.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.AchievementCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of all 316 Pixelclub achievements.
 * Loads declaratively from data-driven JSON schema at startup.
 */
public class AchievementRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Registry");
    private static final Gson GSON = new Gson();

    private final Map<String, Achievement> achievementsById = new ConcurrentHashMap<>();
    private final Map<String, AchievementCategory> categoriesById = new LinkedHashMap<>();
    private final List<AchievementCategory> orderedCategories = new ArrayList<>();

    public boolean loadFromStream(InputStream stream) {
        if (stream == null) {
            LOGGER.error("Cannot load achievements: stream is null");
            return false;
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("categories")) {
                LOGGER.error("Invalid achievements schema: missing 'categories' element");
                return false;
            }

            achievementsById.clear();
            categoriesById.clear();
            orderedCategories.clear();

            JsonArray catArray = root.getAsJsonArray("categories");
            for (JsonElement catEl : catArray) {
                AchievementCategory category = GSON.fromJson(catEl, AchievementCategory.class);
                if (category != null && category.getId() != null) {
                    categoriesById.put(category.getId(), category);
                    orderedCategories.add(category);

                    for (Achievement ach : category.getAchievements()) {
                        if (ach.getId() != null && !ach.getId().isEmpty()) {
                            achievementsById.put(ach.getId(), ach);
                        }
                    }
                }
            }

            LOGGER.info("Successfully loaded {} achievements across {} categories from schema.",
                    achievementsById.size(), categoriesById.size());
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to parse achievements data JSON", e);
            return false;
        }
    }

    public Achievement getAchievement(String id) {
        return id != null ? achievementsById.get(id) : null;
    }

    public AchievementCategory getCategory(String id) {
        return id != null ? categoriesById.get(id) : null;
    }

    public List<AchievementCategory> getCategories() {
        return Collections.unmodifiableList(orderedCategories);
    }

    public Collection<Achievement> getAllAchievements() {
        return Collections.unmodifiableCollection(achievementsById.values());
    }

    public int getTotalCount() {
        return achievementsById.size();
    }
}
