import os
import re
import json

files = [f"transcriptions/batch_{i}.md" for i in range(1, 6)]

categories = {}
current_cat = None
current_sec = None

for fpath in files:
    if not os.path.exists(fpath):
        continue
    with open(fpath, "r", encoding="utf-8") as f:
        lines = f.readlines()
    
    for line in lines:
        cat_match = re.search(r'(?:Selected Category[^:]*|Category Title|Category Header[^:]*):\s*\*?\*?([A-Za-z0-9 &,\'-]+?)(?:\s*\([0-9/ ]+\)|\*|\n|$)', line)
        if cat_match:
            cname = cat_match.group(1).strip()
            # Clean up known category names
            for known in [
                "Catching Pokémon", "Gyms & The Circuit", "Battling", "Raids & Dungeons",
                "Shinies & Legendaries", "Breeding", "Training & Team Building", "Evolving Pokémon",
                "Fishing", "Exploring & Claiming Land", "Mining & Gathering", "Making Money",
                "Automatic Income", "Collecting & Cosmetics", "Social & Clans", "Daily Rewards & Playtime"
            ]:
                if known.lower().replace("é", "e") in cname.lower().replace("é", "e"):
                    cname = known
                    break
            if cname not in categories:
                categories[cname] = {}
            current_cat = cname

        sec_match = re.search(r'###\s+Section:\s*([^\(\n]+)', line)
        if sec_match and current_cat:
            current_sec = sec_match.group(1).strip()
            if current_sec not in categories[current_cat]:
                categories[current_cat][current_sec] = []

print("Categories found:")
for cat, secs in categories.items():
    print(f"- {cat}: {len(secs)} sections ({list(secs.keys())[:3]}...)")
