package com.pixelclub.achievement.ui;

import com.pixelclub.achievement.PxcAchievementsMod;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.AchievementCategory;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side Quest Book & Achievement GUI using SGUI.
 * Follows Pixelclub UI Hard Rules: zero Thai bold (&l/§l), zero glow halo,
 * auto-wrapped descriptions to eliminate cut-offs, and clear progress indicators.
 */
public class AchievementGui extends SimpleGui {

    private final ServerPlayer player;
    private int selectedCategoryIndex = 0;
    private int achievementPage = 0;

    public static void open(ServerPlayer player, String categoryId) {
        AchievementGui gui = new AchievementGui(player);
        if (categoryId != null) {
            List<AchievementCategory> categories = PxcAchievementsMod.getInstance().getRegistry().getCategories();
            for (int i = 0; i < categories.size(); i++) {
                if (categories.get(i).getId().equalsIgnoreCase(categoryId)) {
                    gui.selectedCategoryIndex = i;
                    break;
                }
            }
        }
        gui.build();
        gui.open();
    }

    public AchievementGui(ServerPlayer player) {
        super(MenuType.GENERIC_9x6, player, false);
        this.player = player;
        this.setTitle(Component.literal("Pixelclub — Quest Book"));
    }

    public void build() {
        List<AchievementCategory> categories = PxcAchievementsMod.getInstance().getRegistry().getCategories();
        if (categories.isEmpty()) return;

        if (selectedCategoryIndex >= categories.size()) selectedCategoryIndex = 0;
        AchievementCategory currentCat = categories.get(selectedCategoryIndex);

        // --- ROW 0: Category Selectors ---
        int catStart = (selectedCategoryIndex / 7) * 7;
        setSlot(0, new GuiElementBuilder(Items.ARROW)
                .setName(Component.literal("§e« Previous Categories"))
                .setCallback((idx, type, action) -> {
                    if (selectedCategoryIndex > 0) {
                        selectedCategoryIndex = Math.max(0, selectedCategoryIndex - 7);
                        achievementPage = 0;
                        build();
                    }
                }));

        for (int i = 0; i < 7; i++) {
            int cIdx = catStart + i;
            int slot = i + 1;
            if (cIdx < categories.size()) {
                AchievementCategory cat = categories.get(cIdx);
                boolean isSelected = (cIdx == selectedCategoryIndex);

                int completed = countCompletedInCategory(cat);
                int total = cat.getTotalCount();

                GuiElementBuilder builder = new GuiElementBuilder(isSelected ? Items.ENCHANTED_BOOK : Items.BOOK)
                        .setName(Component.literal((isSelected ? "§b▶ " : "§f") + cat.getName()))
                        .addLoreLine(Component.literal(String.format("§7Progress: §e%d§7/§e%d", completed, total)));

                final int targetCatIdx = cIdx;
                builder.setCallback((idx, type, action) -> {
                    selectedCategoryIndex = targetCatIdx;
                    achievementPage = 0;
                    build();
                });
                setSlot(slot, builder);
            } else {
                setSlot(slot, new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE).setName(Component.literal(" ")));
            }
        }

        setSlot(8, new GuiElementBuilder(Items.ARROW)
                .setName(Component.literal("§eNext Categories »"))
                .setCallback((idx, type, action) -> {
                    if (catStart + 7 < categories.size()) {
                        selectedCategoryIndex = catStart + 7;
                        achievementPage = 0;
                        build();
                    }
                }));

        // --- ROWS 1 to 4: Achievements in Category (36 slots, slots 9..44) ---
        List<Achievement> achs = currentCat.getAchievements();
        int pageSize = 36;
        int maxPages = Math.max(1, (int) Math.ceil((double) achs.size() / pageSize));
        if (achievementPage >= maxPages) achievementPage = maxPages - 1;

        int startIndex = achievementPage * pageSize;
        int endIndex = Math.min(startIndex + pageSize, achs.size());

        for (int i = 0; i < pageSize; i++) {
            int achIdx = startIndex + i;
            int slot = 9 + i;

            if (achIdx < endIndex) {
                Achievement ach = achs.get(achIdx);
                PlayerProgress prog = PxcAchievementsMod.getInstance().getPlayerStore().getProgress(player.getUUID(), ach.getId());

                ClaimState state = prog.getState();
                int currentP = prog.getProgress();
                int targetP = ach.getTarget();

                ItemStack icon = switch (state) {
                    case CLAIMED -> new ItemStack(Items.MINECART);
                    case COMPLETED -> new ItemStack(Items.CHEST_MINECART);
                    case LOCKED -> new ItemStack(Items.HOPPER_MINECART);
                };

                GuiElementBuilder card = new GuiElementBuilder(icon.getItem())
                        .setName(Component.literal("§6" + ach.getName()))
                        .addLoreLine(Component.literal("§8[" + ach.getSection() + "]"))
                        .addLoreLine(Component.literal(" "));

                // Wrap description text to max 38 characters per line to eliminate UI cut-offs
                for (String wrappedLine : wrapText(ach.getDescription(), 38)) {
                    card.addLoreLine(Component.literal("§7" + wrappedLine));
                }

                card.addLoreLine(Component.literal(" "))
                    .addLoreLine(Component.literal(String.format("§fProgress: %s §e%d§7/§e%d",
                            renderProgressBar(currentP, targetP, 10), currentP, targetP)));

                if (!ach.getRewardDisplay().isEmpty()) {
                    card.addLoreLine(Component.literal("§dRewards: §f" + ach.getRewardDisplay()));
                }

                card.addLoreLine(Component.literal(" "));
                if (state == ClaimState.CLAIMED) {
                    card.addLoreLine(Component.literal("§8✔ CLAIMED"));
                } else if (state == ClaimState.COMPLETED) {
                    card.addLoreLine(Component.literal("§a▶ CLICK TO CLAIM REWARD! ◀"));
                    card.setCallback((sIdx, type, action) -> {
                        boolean success = PxcAchievementsMod.getInstance().getPlayerStore().claim(player, ach.getId());
                        if (success) {
                            player.sendSystemMessage(Component.literal("§aClaimed achievement: " + ach.getName()));
                            build();
                        }
                    });
                } else {
                    card.addLoreLine(Component.literal("§c✕ INCOMPLETE"));
                }

                setSlot(slot, card);
            } else {
                setSlot(slot, new GuiElementBuilder(Items.AIR));
            }
        }

        // --- ROW 5: Bottom Controls (slots 45..53) ---
        setSlot(45, new GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(Component.literal("§e« Previous Page"))
                .setCallback((idx, type, action) -> {
                    if (achievementPage > 0) {
                        achievementPage--;
                        build();
                    }
                }));

        int catComp = countCompletedInCategory(currentCat);
        setSlot(49, new GuiElementBuilder(Items.NETHER_STAR)
                .setName(Component.literal("§e" + currentCat.getName()))
                .addLoreLine(Component.literal(String.format("§7Category: §e%d§7/§e%d completed", catComp, currentCat.getTotalCount())))
                .addLoreLine(Component.literal(String.format("§7Page: §e%d§7/§e%d", achievementPage + 1, maxPages))));

        setSlot(53, new GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(Component.literal("§eNext Page »"))
                .setCallback((idx, type, action) -> {
                    if (achievementPage < maxPages - 1) {
                        achievementPage++;
                        build();
                    }
                }));
    }

    private int countCompletedInCategory(AchievementCategory cat) {
        int count = 0;
        for (Achievement a : cat.getAchievements()) {
            PlayerProgress p = PxcAchievementsMod.getInstance().getPlayerStore().getProgress(player.getUUID(), a.getId());
            if (p.isCompleted()) count++;
        }
        return count;
    }

    private String renderProgressBar(int current, int target, int totalBars) {
        float ratio = Math.min(1.0f, Math.max(0.0f, (float) current / target));
        int filled = Math.round(ratio * totalBars);
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < filled; i++) sb.append("■");
        sb.append("§7");
        for (int i = filled; i < totalBars; i++) sb.append("□");
        return sb.toString();
    }

    private List<String> wrapText(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (current.length() + word.length() + 1 > maxChars) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                if (!current.isEmpty()) current.append(" ");
                current.append(word);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }
}
