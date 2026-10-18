import os
import re
import json

files = [f"transcriptions/batch_{i}.md" for i in range(1, 6)]

# Official Category totals from the left navigation pane in Quest Book
CATEGORY_TARGETS = {
    "Catching Pokémon": 25,
    "Gyms & The Circuit": 20,
    "Battling": 18,
    "Raids & Dungeons": 18,
    "Shinies & Legendaries": 17,
    "Breeding": 20,
    "Training & Team Building": 20,
    "Evolving Pokémon": 20,
    "Fishing": 18,
    "Exploring & Claiming Land": 21,
    "Mining & Gathering": 20,
    "Making Money": 20,
    "Automatic Income": 20,
    "Collecting & Cosmetics": 20,
    "Social & Clans": 21,
    "Daily Rewards & Playtime": 18,
}

def normalize_cat(c):
    c = c.replace("Pokmon", "Pokémon").replace("PokAcmon", "Pokémon").strip()
    for known in CATEGORY_TARGETS:
        if known.lower().replace("é", "e") in c.lower().replace("é", "e"):
            return known
    if "Exploring" in c:
        return "Exploring & Claiming Land"
    if "Daily" in c:
        return "Daily Rewards & Playtime"
    if "Training" in c:
        return "Training & Team Building"
    return c

# Structure: data[cat][section][title] = {desc, progress}
data = {cat: {} for cat in CATEGORY_TARGETS}
cat_descriptions = {}

for fpath in files:
    if not os.path.exists(fpath):
        continue
    with open(fpath, "r", encoding="utf-8") as f:
        content = f.read()

    # Split by image sections (## 1. 2026-..., etc.)
    image_blocks = re.split(r'\n##\s+\d+\.\s+', content)
    for block in image_blocks:
        lines = block.splitlines()
        current_cat = None
        current_sec = None
        current_ach = None
        
        for line in lines:
            line_str = line.strip()
            # Category match
            cat_m = re.search(r'(?:Selected Category[^:]*|Category Title):\s*\*?\*?([^\n`\(\*]+)', line_str)
            if cat_m:
                norm = normalize_cat(cat_m.group(1).strip())
                if norm in data:
                    current_cat = norm
            
            # Category description match
            desc_m = re.search(r'(?:Category (?:Subtitle\/)?Description):\s*[`"]?([^\n`"]+)[`"]?', line_str)
            if desc_m and current_cat:
                d_text = desc_m.group(1).strip()
                if current_cat not in cat_descriptions or len(d_text) > len(cat_descriptions[current_cat]):
                    cat_descriptions[current_cat] = d_text

            # Section match: ### Section: NAME (x/y)
            sec_m = re.search(r'###\s+Section:\s*([^\(\n]+)', line_str)
            if sec_m and current_cat:
                sec_name = sec_m.group(1).replace("`", "").strip()
                # Clean up "cont.", "Partially Visible", etc.
                sec_name = re.sub(r'\s*\b(cont\.?|partial(?:ly)?(?:\s+visible)?|end|start)\b', '', sec_name, flags=re.I).strip(' :-[]()')
                current_sec = sec_name
                if current_sec not in data[current_cat]:
                    data[current_cat][current_sec] = {}

            # Achievement title match: e.g. "1. **Gotcha!**" or "**Gotcha!**"
            ach_m = re.search(r'^(?:\d+\.\s+|\-\s+)?\*\*([^*]+)\*\*', line_str)
            if ach_m and current_cat and current_sec:
                title = ach_m.group(1).strip()
                # Exclude meta titles like "Selected Category" or "Category Title" or "Overall Quest Book Progress"
                if not any(k in title for k in ["Selected Category", "Category", "Overall", "Section", "Status", "Description", "Progress"]):
                    title = title.replace("Pokmon", "Pokémon").replace("PokAcmon", "Pokémon")
                    current_ach = title
                    if current_sec not in data[current_cat]:
                        data[current_cat][current_sec] = {}
                    if current_ach not in data[current_cat][current_sec]:
                        data[current_cat][current_sec][current_ach] = {"description": "", "progress": ""}

            # Description match
            desc_match = re.search(r'[-*]\s+\*\*Description:\*\*\s*(.+)', line_str)
            if desc_match and current_cat and current_sec and current_ach:
                desc = desc_match.group(1).strip('`" ').replace("Pokmon", "Pokémon").replace("PokAcmon", "Pokémon").replace("Pok", "Poké").replace("PokAc", "Poké")
                # update if longer
                curr_desc = data[current_cat][current_sec][current_ach]["description"]
                if len(desc) > len(curr_desc):
                    data[current_cat][current_sec][current_ach]["description"] = desc

            # Progress / Target match
            prog_match = re.search(r'[-*]\s+\*\*(?:Progress|Target|Status):\*\*\s*(.+)', line_str)
            if prog_match and current_cat and current_sec and current_ach:
                prog = prog_match.group(1).strip('`" ')
                curr_prog = data[current_cat][current_sec][current_ach]["progress"]
                if not curr_prog or len(prog) > len(curr_prog):
                    data[current_cat][current_sec][current_ach]["progress"] = prog

print("Category summary:")
total_ach = 0
for cat, target in CATEGORY_TARGETS.items():
    sections = data[cat]
    count = sum(len(achs) for achs in sections.values())
    total_ach += count
    print(f"{cat:25}: Found {count:2}/{target:2} achievements in {len(sections)} sections")
print(f"Total achievements captured: {total_ach}/316")
