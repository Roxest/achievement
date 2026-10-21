package com.pixelclub.achievement.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelclub.achievement.PxcAchievementsMod;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.AchievementCategory;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import com.pixelclub.achievement.network.UIPayloadHelper;
import com.pixelclub.achievement.storage.PlayerStore;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Server-side Quest Book scene generator and packet dispatcher.
 * Uses layout: "scene" on pixelclub:ui channel per design-system.md.
 * Follows Pixelclub Hard Rules: zero Thai bold (&l/§l), zero glow halos.
 */
public class AchievementGui {

    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Gui");
    public static final String SCREEN_ID = "quest_book";

    /**
     * Builds and sends the full 2-page Quest Book scene JSON to the player.
     */
    public static void open(ServerPlayer player, String categoryId) {
        if (player == null) return;

        List<AchievementCategory> categories = PxcAchievementsMod.getInstance().getRegistry().getCategories();
        if (categories.isEmpty()) return;

        AchievementCategory currentCat = categories.get(0);
        if (categoryId != null && !categoryId.isEmpty()) {
            for (AchievementCategory cat : categories) {
                if (cat.getId().equalsIgnoreCase(categoryId)) {
                    currentCat = cat;
                    break;
                }
            }
        }

        JsonObject scene = buildSceneJson(player, categories, currentCat);
        UIPayloadHelper.send(player, scene.toString());
    }

    /**
     * Handles client UI packets sent from pxc-client over pixelclub:ui.
     */
    public static void handleClientPacket(ServerPlayer player, String rawJson) {
        if (player == null || rawJson == null || rawJson.isEmpty()) return;

        try {
            JsonObject data = JsonParser.parseString(rawJson).getAsJsonObject();
            String action = data.has("action") ? data.get("action").getAsString() : "";
            String elementId = data.has("elementId") ? data.get("elementId").getAsString() : "";

            // Check if elementId itself is encoded as a nested JSON string
            if (elementId.startsWith("{") && elementId.endsWith("}")) {
                try {
                    JsonObject nested = JsonParser.parseString(elementId).getAsJsonObject();
                    if (nested.has("action")) action = nested.get("action").getAsString();
                    if (nested.has("category_id")) data.add("category_id", nested.get("category_id"));
                    if (nested.has("achievement_id")) data.add("achievement_id", nested.get("achievement_id"));
                } catch (Exception ignored) {}
            }

            // Normalise action and targets
            String targetCategory = data.has("category_id") ? data.get("category_id").getAsString() : null;
            String targetAchievement = data.has("achievement_id") ? data.get("achievement_id").getAsString() : null;

            if (elementId.startsWith("select_category:")) {
                action = "select_category";
                targetCategory = elementId.substring("select_category:".length());
            } else if (elementId.startsWith("claim:")) {
                action = "claim";
                targetAchievement = elementId.substring("claim:".length());
            } else if (elementId.startsWith("view_category_rewards:")) {
                action = "view_category_rewards";
                targetCategory = elementId.substring("view_category_rewards:".length());
            } else if (elementId.equalsIgnoreCase("close") || action.equalsIgnoreCase("ui_close") || action.equalsIgnoreCase("close")) {
                action = "close";
            }

            PlayerStore store = PxcAchievementsMod.getInstance().getPlayerStore();

            switch (action) {
                case "select_category" -> {
                    open(player, targetCategory);
                }
                case "claim" -> {
                    Achievement ach = targetAchievement != null ? PxcAchievementsMod.getInstance().getRegistry().getAchievement(targetAchievement) : null;
                    if (targetAchievement != null && store != null) {
                        boolean claimed = store.claim(player, targetAchievement);
                        if (claimed && ach != null) {
                            player.sendSystemMessage(Component.literal("§a✔ Claimed achievement: §e" + ach.getName()));
                        }
                    }
                    open(player, ach != null ? ach.getCategoryId() : targetCategory);
                }
                case "view_category_rewards" -> {
                    player.sendSystemMessage(Component.literal("§e[Quest Book] §7Previewing milestone rewards for category: §6" + targetCategory));
                    open(player, targetCategory);
                }
                case "close" -> {
                    JsonObject closePacket = new JsonObject();
                    closePacket.addProperty("action", "close_screen");
                    UIPayloadHelper.send(player, closePacket.toString());
                }
                default -> {
                    LOGGER.debug("Unhandled UI action '{}' from player {}", action, player.getName().getString());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse client UI payload: {}", rawJson, e);
        }
    }

    static JsonObject buildSceneJson(ServerPlayer player, List<AchievementCategory> categories, AchievementCategory currentCat) {
        PlayerStore store = PxcAchievementsMod.getInstance().getPlayerStore();
        return buildSceneJson(player.getUUID(), store, categories, currentCat);
    }

    public static JsonObject buildSceneJson(UUID uuid, PlayerStore store, List<AchievementCategory> categories, AchievementCategory currentCat) {
        int totalCompleted = 0;
        int totalAchievements = 0;
        Map<String, Integer> categoryCompletedMap = new HashMap<>();

        for (AchievementCategory cat : categories) {
            int completedInCat = 0;
            for (Achievement ach : cat.getAchievements()) {
                totalAchievements++;
                PlayerProgress p = store != null ? store.getProgress(uuid, ach.getId()) : new PlayerProgress();
                if (p.isCompleted()) {
                    completedInCat++;
                    totalCompleted++;
                }
            }
            categoryCompletedMap.put(cat.getId(), completedInCat);
        }

        JsonObject scene = new JsonObject();
        scene.addProperty("action", "open_screen");
        scene.addProperty("layout", "scene");
        scene.addProperty("id", SCREEN_ID);
        scene.addProperty("title", "QUEST BOOK");
        scene.addProperty("bg", "none");
        scene.addProperty("smooth", true);
        scene.addProperty("esc_action", "close");

        JsonArray viewport = new JsonArray();
        viewport.add(800);
        viewport.add(480);
        scene.add("viewport", viewport);

        // Root Node (spans full canvas, centers book frame)
        JsonObject root = new JsonObject();
        root.addProperty("type", "column");
        root.addProperty("align", "center");
        root.addProperty("justify", "center");
        root.addProperty("w", "fill");
        root.addProperty("h", "fill");

        // Book Frame (780 x 460)
        JsonObject bookFrame = new JsonObject();
        bookFrame.addProperty("type", "column");
        bookFrame.addProperty("w", 780);
        bookFrame.addProperty("h", 460);
        bookFrame.addProperty("fill", "#26160B");
        bookFrame.addProperty("border", "#4E2C14");
        bookFrame.addProperty("border_w", 3);
        bookFrame.addProperty("radius", 10);
        bookFrame.addProperty("pad", 8);
        bookFrame.addProperty("gap", 6);

        // 1. Top Bar Header
        bookFrame.add("children", new JsonArray());
        bookFrame.getAsJsonArray("children").add(buildTopBar(totalCompleted, totalAchievements));

        // 2. Main 2-Page Horizontal Container
        JsonObject pagesRow = new JsonObject();
        pagesRow.addProperty("type", "row");
        pagesRow.addProperty("w", "fill");
        pagesRow.addProperty("h", "fill");
        pagesRow.addProperty("gap", 8);
        pagesRow.add("children", new JsonArray());

        // Left Page: All 16 Categories (Fixed / Non-paginated)
        pagesRow.getAsJsonArray("children").add(buildLeftPage(categories, currentCat, categoryCompletedMap));

        // Right Page: Category Header + Scrollable Achievements
        pagesRow.getAsJsonArray("children").add(buildRightPage(uuid, store, currentCat, categoryCompletedMap.getOrDefault(currentCat.getId(), 0)));

        bookFrame.getAsJsonArray("children").add(pagesRow);
        root.add("children", new JsonArray());
        root.getAsJsonArray("children").add(bookFrame);

        scene.add("root", root);
        return scene;
    }

    private static JsonObject buildTopBar(int completed, int total) {
        JsonObject bar = new JsonObject();
        bar.addProperty("type", "row");
        bar.addProperty("w", "fill");
        bar.addProperty("h", 28);
        bar.addProperty("align", "center");
        bar.addProperty("pad_l", 4);
        bar.addProperty("pad_r", 4);

        JsonArray children = new JsonArray();

        // Overall Progress Pill (Top-Left)
        JsonObject progressPill = new JsonObject();
        progressPill.addProperty("type", "column");
        progressPill.addProperty("w", 130);
        progressPill.addProperty("gap", 2);

        JsonArray pillChildren = new JsonArray();
        JsonObject pillText = new JsonObject();
        pillText.addProperty("type", "text");
        pillText.addProperty("text", completed + "/" + total);
        pillText.addProperty("color", "#FCD34D");
        pillText.addProperty("size", 0.85);
        pillChildren.add(pillText);

        JsonObject progressBar = new JsonObject();
        progressBar.addProperty("type", "progress");
        progressBar.addProperty("w", 120);
        progressBar.addProperty("h", 5);
        progressBar.addProperty("radius", 2);
        JsonObject progObj = new JsonObject();
        progObj.addProperty("cur", completed);
        progObj.addProperty("total", Math.max(1, total));
        progObj.addProperty("fill", "#F59E0B");
        progObj.addProperty("track", "#170D06");
        progressBar.add("progress", progObj);
        pillChildren.add(progressBar);

        progressPill.add("children", pillChildren);
        children.add(progressPill);

        // Center Ribbon Spacer Left
        JsonObject spacer1 = new JsonObject();
        spacer1.addProperty("type", "spacer");
        spacer1.addProperty("w", "fill");
        children.add(spacer1);

        // Center Header Ribbon
        JsonObject ribbon = new JsonObject();
        ribbon.addProperty("type", "column");
        ribbon.addProperty("w", 160);
        ribbon.addProperty("h", 24);
        ribbon.addProperty("fill", "#3B1E08");
        ribbon.addProperty("border", "#8C5A2B");
        ribbon.addProperty("border_w", 2);
        ribbon.addProperty("radius", 4);
        ribbon.addProperty("align", "center");
        ribbon.addProperty("justify", "center");

        JsonArray ribbonChildren = new JsonArray();
        JsonObject ribbonText = new JsonObject();
        ribbonText.addProperty("type", "text");
        ribbonText.addProperty("text", "QUEST BOOK");
        ribbonText.addProperty("color", "#F6D896");
        ribbonText.addProperty("size", 1.1);
        ribbonChildren.add(ribbonText);
        ribbon.add("children", ribbonChildren);
        children.add(ribbon);

        // Spacer Right
        JsonObject spacer2 = new JsonObject();
        spacer2.addProperty("type", "spacer");
        spacer2.addProperty("w", "fill");
        children.add(spacer2);

        // Close Button [X]
        JsonObject closeBtn = new JsonObject();
        closeBtn.addProperty("type", "column");
        closeBtn.addProperty("w", 24);
        closeBtn.addProperty("h", 22);
        closeBtn.addProperty("fill", "#B71C1C");
        closeBtn.addProperty("border", "#EF5350");
        closeBtn.addProperty("border_w", 1);
        closeBtn.addProperty("radius", 4);
        closeBtn.addProperty("align", "center");
        closeBtn.addProperty("justify", "center");
        closeBtn.addProperty("action", "close");

        JsonArray closeChildren = new JsonArray();
        JsonObject xText = new JsonObject();
        xText.addProperty("type", "text");
        xText.addProperty("text", "✕");
        xText.addProperty("color", "#FFFFFF");
        xText.addProperty("size", 1.0);
        closeChildren.add(xText);
        closeBtn.add("children", closeChildren);
        children.add(closeBtn);

        bar.add("children", children);
        return bar;
    }

    private static JsonObject buildLeftPage(List<AchievementCategory> categories, AchievementCategory selectedCat, Map<String, Integer> compMap) {
        JsonObject page = new JsonObject();
        page.addProperty("type", "column");
        page.addProperty("w", 260);
        page.addProperty("h", "fill");
        page.addProperty("fill", "#1F120A");
        page.addProperty("border", "#3A2111");
        page.addProperty("border_w", 1.5);
        page.addProperty("radius", 6);
        page.addProperty("pad", 4);
        page.addProperty("gap", 2);

        JsonArray rows = new JsonArray();
        for (AchievementCategory cat : categories) {
            boolean isSelected = cat.getId().equalsIgnoreCase(selectedCat.getId());
            int completed = compMap.getOrDefault(cat.getId(), 0);

            JsonObject row = new JsonObject();
            row.addProperty("type", "row");
            row.addProperty("w", "fill");
            row.addProperty("h", 21);
            row.addProperty("pad_l", 4);
            row.addProperty("pad_r", 4);
            row.addProperty("align", "center");
            row.addProperty("radius", 4);
            row.addProperty("fill", isSelected ? "#4A2E19" : "#180D06");
            row.addProperty("border", isSelected ? "#F59E0B" : "#2B160A");
            row.addProperty("border_w", isSelected ? 2 : 1);
            row.addProperty("action", "select_category:" + cat.getId());

            JsonArray rowChildren = new JsonArray();

            // Category Icon
            JsonObject icon = new JsonObject();
            icon.addProperty("type", "item");
            icon.addProperty("item", getCategoryIconItem(cat.getId()));
            icon.addProperty("w", 16);
            icon.addProperty("h", 16);
            rowChildren.add(icon);

            JsonObject iconSpacer = new JsonObject();
            iconSpacer.addProperty("type", "spacer");
            iconSpacer.addProperty("w", 4);
            rowChildren.add(iconSpacer);

            // Category Title (cleanly truncated)
            JsonObject title = new JsonObject();
            title.addProperty("type", "text");
            title.addProperty("text", truncate(cat.getName(), 18));
            title.addProperty("color", isSelected ? "#FFFFFF" : "#D4B886");
            title.addProperty("size", 0.82);
            title.addProperty("w", "fill");
            rowChildren.add(title);

            // Counter ratio
            JsonObject counter = new JsonObject();
            counter.addProperty("type", "text");
            counter.addProperty("text", completed + "/" + cat.getTotalCount());
            counter.addProperty("color", isSelected ? "#FCD34D" : "#8C7B68");
            counter.addProperty("size", 0.78);
            counter.addProperty("align_text", "right");
            rowChildren.add(counter);

            row.add("children", rowChildren);
            rows.add(row);
        }

        page.add("children", rows);
        return page;
    }

    private static JsonObject buildRightPage(UUID uuid, PlayerStore store, AchievementCategory currentCat, int catCompleted) {
        JsonObject page = new JsonObject();
        page.addProperty("type", "column");
        page.addProperty("w", "fill");
        page.addProperty("h", "fill");
        page.addProperty("gap", 6);

        JsonArray pageChildren = new JsonArray();

        // 1. Top Summary Banner
        JsonObject banner = new JsonObject();
        banner.addProperty("type", "column");
        banner.addProperty("w", "fill");
        banner.addProperty("fill", "#361F10");
        banner.addProperty("border", "#55331C");
        banner.addProperty("border_w", 1.5);
        banner.addProperty("radius", 6);
        banner.addProperty("pad", 6);
        banner.addProperty("gap", 3);

        JsonArray bannerChildren = new JsonArray();

        // Banner Row 1: Title + Counter + Rewards Button
        JsonObject bRow1 = new JsonObject();
        bRow1.addProperty("type", "row");
        bRow1.addProperty("w", "fill");
        bRow1.addProperty("align", "center");

        JsonArray bRow1Children = new JsonArray();
        JsonObject catTitle = new JsonObject();
        catTitle.addProperty("type", "text");
        catTitle.addProperty("text", currentCat.getName());
        catTitle.addProperty("color", "#F6D896");
        catTitle.addProperty("size", 1.05);
        bRow1Children.add(catTitle);

        JsonObject sp = new JsonObject();
        sp.addProperty("type", "spacer");
        sp.addProperty("w", "fill");
        bRow1Children.add(sp);

        JsonObject catRatio = new JsonObject();
        catRatio.addProperty("type", "text");
        catRatio.addProperty("text", catCompleted + " / " + currentCat.getTotalCount());
        catRatio.addProperty("color", "#FCD34D");
        catRatio.addProperty("size", 0.95);
        bRow1Children.add(catRatio);

        JsonObject sp2 = new JsonObject();
        sp2.addProperty("type", "spacer");
        sp2.addProperty("w", 6);
        bRow1Children.add(sp2);

        // Rewards button
        JsonObject rewBtn = new JsonObject();
        rewBtn.addProperty("type", "row");
        rewBtn.addProperty("fill", "#2E7D32");
        rewBtn.addProperty("border", "#4CAF50");
        rewBtn.addProperty("border_w", 1);
        rewBtn.addProperty("radius", 3);
        rewBtn.addProperty("pad", 3);
        rewBtn.addProperty("pad_l", 6);
        rewBtn.addProperty("pad_r", 6);
        rewBtn.addProperty("action", "view_category_rewards:" + currentCat.getId());

        JsonArray rewBtnChildren = new JsonArray();
        JsonObject rewText = new JsonObject();
        rewText.addProperty("type", "text");
        rewText.addProperty("text", "🎁 REWARDS");
        rewText.addProperty("color", "#FFFFFF");
        rewText.addProperty("size", 0.8);
        rewBtnChildren.add(rewText);
        rewBtn.add("children", rewBtnChildren);
        bRow1Children.add(rewBtn);

        bRow1.add("children", bRow1Children);
        bannerChildren.add(bRow1);

        // Banner Row 2: Subtitle / Theme Description
        JsonObject desc = new JsonObject();
        desc.addProperty("type", "text");
        desc.addProperty("text", currentCat.getDescription());
        desc.addProperty("color", "#BFA586");
        desc.addProperty("size", 0.75);
        bannerChildren.add(desc);

        // Banner Row 3: Horizontal Progress Bar
        JsonObject bar = new JsonObject();
        bar.addProperty("type", "progress");
        bar.addProperty("w", "fill");
        bar.addProperty("h", 6);
        bar.addProperty("radius", 2);
        JsonObject prog = new JsonObject();
        prog.addProperty("cur", catCompleted);
        prog.addProperty("total", Math.max(1, currentCat.getTotalCount()));
        prog.addProperty("fill", "#F59E0B");
        prog.addProperty("track", "#1E1007");
        bar.add("progress", prog);
        bannerChildren.add(bar);

        banner.add("children", bannerChildren);
        pageChildren.add(banner);

        // 2. Scrollable Content Viewport
        JsonObject scroll = new JsonObject();
        scroll.addProperty("type", "scroll");
        scroll.addProperty("dir", "y");
        scroll.addProperty("w", "fill");
        scroll.addProperty("h", "fill");
        scroll.addProperty("pad_r", 4);

        // Group achievements by section in preserved order
        Map<String, List<Achievement>> sectionMap = new LinkedHashMap<>();
        for (Achievement a : currentCat.getAchievements()) {
            String sec = a.getSection() != null && !a.getSection().isEmpty() ? a.getSection() : "GENERAL";
            sectionMap.computeIfAbsent(sec, k -> new ArrayList<>()).add(a);
        }

        JsonObject scrollContent = new JsonObject();
        scrollContent.addProperty("type", "column");
        scrollContent.addProperty("w", "fill");
        scrollContent.addProperty("gap", 6);
        JsonArray scrollChildren = new JsonArray();

        for (Map.Entry<String, List<Achievement>> entry : sectionMap.entrySet()) {
            String sectionName = entry.getKey();
            List<Achievement> list = entry.getValue();

            int secCompleted = 0;
            for (Achievement a : list) {
                if (store != null && store.getProgress(uuid, a.getId()).isCompleted()) {
                    secCompleted++;
                }
            }

            // Section Header
            JsonObject secHeader = new JsonObject();
            secHeader.addProperty("type", "column");
            secHeader.addProperty("w", "fill");
            secHeader.addProperty("gap", 2);
            secHeader.addProperty("pad_t", 4);

            JsonArray headerItems = new JsonArray();
            JsonObject headerRow = new JsonObject();
            headerRow.addProperty("type", "row");
            headerRow.addProperty("w", "fill");
            headerRow.addProperty("align", "center");

            JsonArray hRowChildren = new JsonArray();
            JsonObject sTitle = new JsonObject();
            sTitle.addProperty("type", "text");
            sTitle.addProperty("text", sectionName.toUpperCase());
            sTitle.addProperty("color", "#F6D896");
            sTitle.addProperty("size", 0.88);
            hRowChildren.add(sTitle);

            JsonObject sp3 = new JsonObject();
            sp3.addProperty("type", "spacer");
            sp3.addProperty("w", "fill");
            hRowChildren.add(sp3);

            JsonObject sCount = new JsonObject();
            sCount.addProperty("type", "text");
            sCount.addProperty("text", secCompleted + "/" + list.size());
            sCount.addProperty("color", "#FCD34D");
            sCount.addProperty("size", 0.82);
            hRowChildren.add(sCount);

            headerRow.add("children", hRowChildren);
            headerItems.add(headerRow);

            JsonObject divider = new JsonObject();
            divider.addProperty("type", "rect");
            divider.addProperty("w", "fill");
            divider.addProperty("h", 1);
            divider.addProperty("fill", "#4A2B15");
            headerItems.add(divider);

            secHeader.add("children", headerItems);
            scrollChildren.add(secHeader);

            // Achievement Cards in Section
            for (Achievement ach : list) {
                PlayerProgress p = store != null ? store.getProgress(uuid, ach.getId()) : new PlayerProgress();
                ClaimState state = p.getState();

                JsonObject card = new JsonObject();
                card.addProperty("type", "row");
                card.addProperty("w", "fill");
                card.addProperty("fill", "#27150A");
                card.addProperty("border", "#3E2211");
                card.addProperty("border_w", 1);
                card.addProperty("radius", 4);
                card.addProperty("pad", 6);
                card.addProperty("gap", 6);
                card.addProperty("align", "center");

                JsonArray cardChildren = new JsonArray();

                // Difficulty/Tier Dot
                JsonObject dot = new JsonObject();
                dot.addProperty("type", "column");
                dot.addProperty("w", 6);
                dot.addProperty("h", 6);
                dot.addProperty("radius", 3);
                dot.addProperty("fill", getTierDotColor(ach.getTarget()));
                cardChildren.add(dot);

                // Title & Description Column
                JsonObject textCol = new JsonObject();
                textCol.addProperty("type", "column");
                textCol.addProperty("w", "fill");
                textCol.addProperty("gap", 2);

                JsonArray tChildren = new JsonArray();
                JsonObject aName = new JsonObject();
                aName.addProperty("type", "text");
                aName.addProperty("text", ach.getName());
                aName.addProperty("color", "#FFF4E0");
                aName.addProperty("size", 0.88);
                tChildren.add(aName);

                JsonObject aDesc = new JsonObject();
                aDesc.addProperty("type", "text");
                aDesc.addProperty("text", ach.getDescription());
                aDesc.addProperty("color", "#BFA88F");
                aDesc.addProperty("size", 0.74);
                tChildren.add(aDesc);

                if (ach.getRewardDisplay() != null && !ach.getRewardDisplay().isEmpty()) {
                    JsonObject aReward = new JsonObject();
                    aReward.addProperty("type", "text");
                    aReward.addProperty("text", "✦ Reward: " + ach.getRewardDisplay());
                    aReward.addProperty("color", "#FFB300");
                    aReward.addProperty("size", 0.70);
                    tChildren.add(aReward);
                }

                textCol.add("children", tChildren);
                cardChildren.add(textCol);

                // Right-Side Action / Progress Area
                JsonObject actionArea = new JsonObject();
                actionArea.addProperty("type", "column");
                actionArea.addProperty("w", 85);
                actionArea.addProperty("align", "center");
                actionArea.addProperty("justify", "center");

                JsonArray aAreaChildren = new JsonArray();

                if (state == ClaimState.LOCKED) {
                    JsonObject pText = new JsonObject();
                    pText.addProperty("type", "text");
                    pText.addProperty("text", formatNumber(p.getProgress()) + "/" + formatNumber(ach.getTarget()));
                    pText.addProperty("color", "#FCD34D");
                    pText.addProperty("size", 0.74);
                    aAreaChildren.add(pText);

                    JsonObject aBar = new JsonObject();
                    aBar.addProperty("type", "progress");
                    aBar.addProperty("w", 75);
                    aBar.addProperty("h", 5);
                    aBar.addProperty("radius", 2);
                    JsonObject apObj = new JsonObject();
                    apObj.addProperty("cur", p.getProgress());
                    apObj.addProperty("total", Math.max(1, ach.getTarget()));
                    apObj.addProperty("fill", "#E65100");
                    apObj.addProperty("track", "#140A04");
                    aBar.add("progress", apObj);
                    aAreaChildren.add(aBar);
                } else if (state == ClaimState.COMPLETED) {
                    JsonObject claimBtn = new JsonObject();
                    claimBtn.addProperty("type", "row");
                    claimBtn.addProperty("w", 80);
                    claimBtn.addProperty("h", 20);
                    claimBtn.addProperty("fill", "#2E7D32");
                    claimBtn.addProperty("border", "#66BB6A");
                    claimBtn.addProperty("border_w", 1);
                    claimBtn.addProperty("radius", 3);
                    claimBtn.addProperty("align", "center");
                    claimBtn.addProperty("justify", "center");
                    claimBtn.addProperty("action", "claim:" + ach.getId());

                    JsonArray cBtnChildren = new JsonArray();
                    JsonObject cText = new JsonObject();
                    cText.addProperty("type", "text");
                    cText.addProperty("text", "✔ CLAIM");
                    cText.addProperty("color", "#FFFFFF");
                    cText.addProperty("size", 0.82);
                    cBtnChildren.add(cText);
                    claimBtn.add("children", cBtnChildren);
                    aAreaChildren.add(claimBtn);
                } else { // CLAIMED
                    JsonObject claimedBadge = new JsonObject();
                    claimedBadge.addProperty("type", "row");
                    claimedBadge.addProperty("w", 80);
                    claimedBadge.addProperty("h", 18);
                    claimedBadge.addProperty("fill", "#1A0E07");
                    claimedBadge.addProperty("border", "#331C0E");
                    claimedBadge.addProperty("border_w", 1);
                    claimedBadge.addProperty("radius", 3);
                    claimedBadge.addProperty("align", "center");
                    claimedBadge.addProperty("justify", "center");

                    JsonArray badgeChildren = new JsonArray();
                    JsonObject bText = new JsonObject();
                    bText.addProperty("type", "text");
                    bText.addProperty("text", "✔ CLAIMED");
                    bText.addProperty("color", "#888888");
                    bText.addProperty("size", 0.74);
                    badgeChildren.add(bText);
                    claimedBadge.add("children", badgeChildren);
                    aAreaChildren.add(claimedBadge);
                }

                actionArea.add("children", aAreaChildren);
                cardChildren.add(actionArea);

                card.add("children", cardChildren);
                scrollChildren.add(card);
            }
        }

        scrollContent.add("children", scrollChildren);
        scroll.add("children", new JsonArray());
        scroll.getAsJsonArray("children").add(scrollContent);

        pageChildren.add(scroll);
        page.add("children", pageChildren);
        return page;
    }

    private static String getCategoryIconItem(String catId) {
        boolean cobblemon = FabricLoader.getInstance().isModLoaded("cobblemon");
        return switch (catId.toLowerCase()) {
            case "catching" -> cobblemon ? "cobblemon:poke_ball" : "minecraft:lead";
            case "gyms" -> "minecraft:iron_nugget";
            case "battling" -> "minecraft:iron_sword";
            case "raids" -> "minecraft:wither_skeleton_skull";
            case "shinies" -> "minecraft:nether_star";
            case "breeding" -> "minecraft:egg";
            case "training" -> "minecraft:anvil";
            case "evolving" -> cobblemon ? "cobblemon:fire_stone" : "minecraft:fire_charge";
            case "fishing" -> "minecraft:fishing_rod";
            case "exploring" -> "minecraft:compass";
            case "mining" -> "minecraft:diamond_pickaxe";
            case "economy" -> "minecraft:gold_ingot";
            case "automation" -> "minecraft:hopper";
            case "cosmetics" -> "minecraft:armor_stand";
            case "social" -> "minecraft:player_head";
            case "daily" -> "minecraft:clock";
            default -> "minecraft:book";
        };
    }

    private static String getTierDotColor(int target) {
        if (target <= 5) return "#4CAF50";      // Green (starter/tier 1)
        if (target <= 100) return "#FF9800";    // Orange (mid-tier)
        return "#F44336";                       // Red (hard/endgame)
    }

    private static String formatNumber(int val) {
        if (val >= 1000) {
            if (val % 1000 == 0) return (val / 1000) + "k";
            return String.format(Locale.US, "%.1fk", val / 1000.0);
        }
        return String.valueOf(val);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 2) + "...";
    }
}
