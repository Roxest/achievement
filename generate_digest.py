import os
import json
from complete_achievements import FULL_SPEC, CATEGORY_THEMES

digest_path = r"C:\Users\thamm\Downloads\pixelclub-handoff-20261014\pixelclub-handoff-20261014\KrausVault-20261014\KrausVault\Pixelclub\port\digest-achievements-spec-gaps.md"
os.makedirs(os.path.dirname(digest_path), exist_ok=True)

# Count stats
counts_by_type = {"TRANSCRIBED": 0, "COMPLETED_TRUNCATION": 0, "DRAFTED_CUTOFF": 0, "DRAFTED_MISSING_SCREENSHOTS": 0}
for cat, secs in FULL_SPEC.items():
    for sname, achs in secs.items():
        for title, desc, tgt, rew, gtype in achs:
            counts_by_type[gtype] += 1

with open(digest_path, "w", encoding="utf-8") as f:
    f.write("---\n")
    f.write("tags: [pixelclub, achievements, port, digest, spec, gaps]\n")
    f.write("updated: 2026-10-18\n")
    f.write("status: waiting-kraus\n")
    f.write("---\n\n")

    f.write("# Digest: Achievement System Specification Gap Audit & Draft Completion\n\n")
    f.write("**Status:** `waiting-kraus` · **Coverage:** 316/316 (100.0%) · **Reference Spec:** `Achievement/ACHIEVEMENTS_SPEC.md`\n\n")

    f.write("## 1. Executive Summary\n\n")
    f.write("During the transcription of the legacy in-game achievement system from screenshots, gaps were identified due to UI scrolling cut-offs and missing screenshot files for Category 9 (Fishing).\n")
    f.write("Because legacy server backend files are not locally present, all missing descriptions, target numbers, and reward structures have been drafted based on:\n")
    f.write("1. **Achievement Titles & In-Game UI Context** (OCR prefixes, button states, progress bars).\n")
    f.write("2. **Cobblemon & Minecraft Mechanics** (breeding items, IV/EV caps, type gems, fossil machines, apricorns, mount system).\n")
    f.write("3. **Pixel Club Server Systems** (economy commands `/bank`, `/banknote`, `/ah`, sell wands, minions, workstations, clan challenges, daily rewards, raid coin shop, crate keys).\n\n")

    f.write("### Audit Metrics\n\n")
    f.write("| Status Category | Count | % of Total | Description |\n")
    f.write("|---|:---:|:---:|---|\n")
    f.write(f"| **Fully Transcribed** | {counts_by_type['TRANSCRIBED']} | {counts_by_type['TRANSCRIBED']/316*100:.1f}% | Full description and target cleanly visible in original UI |\n")
    f.write(f"| **Drafted Cut-Offs** | {counts_by_type['DRAFTED_CUTOFF']} | {counts_by_type['DRAFTED_CUTOFF']/316*100:.1f}% | Categories 4-7, 11-14 cut off by UI scroll container; restored using batch OCR data & game rules |\n")
    f.write(f"| **Completed Truncations** | {counts_by_type['COMPLETED_TRUNCATION']} | {counts_by_type['COMPLETED_TRUNCATION']/316*100:.1f}% | Sentences ending with `...` in UI text lines completed to full grammatical clarity |\n")
    f.write(f"| **Drafted Fishing (Cat 9)** | {counts_by_type['DRAFTED_MISSING_SCREENSHOTS']} | {counts_by_type['DRAFTED_MISSING_SCREENSHOTS']/316*100:.1f}% | Complete 18-achievement progression drafted to replace missing screenshots |\n")
    f.write("| **TOTAL** | **316** | **100.0%** | Full canonical dataset for Fabric mod scaffolding |\n\n")

    f.write("---\n\n")
    f.write("## 2. Reward Structure & Economy Philosophy\n\n")
    f.write("Proposed rewards are structured into four distinct progression tiers aligned with server economy balance:\n")
    f.write("- **Tier 1 (Novice / Rookie):** 100 - 500 PokéCoins, basic consumables (Poké Balls, Great Balls, potions, bread, tokens).\n")
    f.write("- **Tier 2 (Adept / Intermediate):** 500 - 2,500 PokéCoins, 1x Trainer Crate Key, specialized items (Ultra Balls, Rare Candies, evolution stones, Power items).\n")
    f.write("- **Tier 3 (Veteran / Advanced):** 2,500 - 10,000 PokéCoins, 1x Ultra Crate Key, high-value utilities (Bottle Caps, Destiny Knot, Mint Vouchers, Ability Capsules, Type Gems).\n")
    f.write("- **Tier 4 (Master / Pinnacle):** 10,000 - 50,000 PokéCoins, Master Crate Keys / Special Crate Keys (Sub-Legendary, Legendary, Mythical), Gold Bottle Caps, Master Ball, and exclusive cosmetic chat titles.\n\n")

    f.write("---\n\n")
    f.write("## 3. Comprehensive Breakdown of Drafted & Restored Achievements\n\n")

    for cat_idx, (cat, secs) in enumerate(FULL_SPEC.items(), 1):
        gapped_in_cat = [a for s in secs.values() for a in s if a[4] != 'TRANSCRIBED']
        if not gapped_in_cat:
            continue
        
        f.write(f"### Category {cat_idx}: {cat} ({len(gapped_in_cat)} items reviewed/drafted)\n\n")
        f.write(f"**Theme:** *{CATEGORY_THEMES[cat]}*\n\n")
        f.write("| Section | Achievement | Status | Drafted / Restored Description | Target | Proposed Reward |\n")
        f.write("|---|---|:---:|---|:---:|---|\n")

        for sname, ach_list in secs.items():
            for title, desc, target, rew, gtype in ach_list:
                if gtype == 'TRANSCRIBED':
                    continue
                tag = "Drafted (Cut-off)" if gtype == "DRAFTED_CUTOFF" else ("Drafted (Cat 9)" if gtype == "DRAFTED_MISSING_SCREENSHOTS" else "Completed (...)")
                f.write(f"| {sname} | **{title}** | `{tag}` | {desc} | `{target}` | {rew} |\n")
        f.write("\n")

    f.write("---\n\n")
    f.write("## 4. Specific Category Deep Dives\n\n")
    f.write("### Category 4: Raids & Dungeons (18 achievements)\n")
    f.write("- **Gaps identified:** All 18 descriptions were cut off by UI scroll. Targets were partially preserved in batch OCR (`0/1`, `0/10`, `0/25k`).\n")
    f.write("- **Drafting rationale:** Aligned with Pixel Club raid star ratings (1-star to 6-star ladder), Raid Coins shop (`/raid`), and the 4 dungeon difficulties (Easy/Nether, Medium, Hard, Extreme).\n\n")

    f.write("### Category 5: Shinies & Legendaries (17 achievements)\n")
    f.write("- **Gaps identified:** 17 descriptions cut off in UI scroll.\n")
    f.write("- **Drafting rationale:** Built around server-wide Scout events (Shiny Scout, Legendary Scout), Masuda breeding method, /spinner mechanics, and Pokédex shiny tracking.\n\n")

    f.write("### Category 6: Breeding (20 achievements)\n")
    f.write("- **Gaps identified:** 20 descriptions cut off in UI scroll.\n")
    f.write("- **Drafting rationale:** Pasture blocks, Flame Body hatching acceleration, IV inheritance with Destiny Knot and Power items, Everstone Nature passing, Ball inheritance, Mirror Herb egg moves, and hundo selling on Auction House.\n\n")

    f.write("### Category 7: Training & Team Building (20 achievements)\n")
    f.write("- **Gaps identified:** Sections 1 & 2 (Tune Your Team, EV Training) cut off. Truncations in Type Gems and Perfect Specimen.\n")
    f.write("- **Drafting rationale:** Cobblemon vitamins (Protein, Iron, etc.), mochis (Fresh-Start Mochi), Ability Patch/Capsule, Nature Mints, Suspicious block brushing for Type Gems, and competitive team construction.\n\n")

    f.write("### Category 9: Fishing (18 achievements)\n")
    f.write("- **Gaps identified:** Entire screenshot batch absent.\n")
    f.write("- **Drafting rationale:** Old/Good/Super Rod progression, specialized bait/lures, lava fishing, Colossal/deep-sea catches, server fishing tournaments, Clan Challenge contributions, and 1,000 catches milestone.\n\n")

    f.write("### Categories 11, 12, 13, 14 (Economy, Automation, Mining, Cosmetics)\n")
    f.write("- **Gaps identified:** Cut-offs in Master Gatherer, Spend Smart, Earning Your Keep, Auction Game, Sell Wands, Tycoon, Minions, Work Stations, Hoppers, AutoSell Chests, Dialga statue boost, Sizing brackets (XXS to XXXL), Plushies, and Cosmetics/Tags/Auras.\n")
    f.write("- **Drafting rationale:** Reconstructed exact server commands (`/bank`, `/banknote`, `/ah`, `/sell hand`, `/mergewands`, `/stationstrader`, `/cosmetics`, `/tags`) and quantitative progression targets.\n\n")

    f.write("---\n\n")
    f.write("## 5. Review Checklist for Kraus (`status: waiting-kraus`)\n\n")
    f.write("- [ ] **Confirm Category 9 (Fishing) scope:** 18 achievements, 5 sections (First Cast, Angler's Luck, Big Game Hunter, Tournament Time, Master of the Seas).\n")
    f.write("- [ ] **Validate Raid Coin costs:** 25,000 Raid Coins for `Big Spender`.\n")
    f.write("- [ ] **Validate Tycoon income milestones:** 50,000 to 100,000,000 PokéCoins.\n")
    f.write("- [ ] **Validate Work Station tiers:** Tier 1 through Tier 5 max production.\n")
    f.write("- [ ] **Approve proposed reward table:** Balance of PokéCoins, Crate Keys, and titles before implementation in Fabric JSON data assets.\n")

print(f"Generated {digest_path} successfully!")
