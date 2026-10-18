import json
import os
import re

with open('ACHIEVEMENTS_DATA.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

def slugify(text):
    s = text.lower()
    s = re.sub(r'[^a-z0-9]+', '_', s)
    return s.strip('_')

cat_slugs = {
    'Catching Pokémon': 'catching',
    'Gyms & The Circuit': 'gyms',
    'Battling': 'battling',
    'Raids & Dungeons': 'raids',
    'Shinies & Legendaries': 'shinies',
    'Breeding': 'breeding',
    'Training & Team Building': 'training',
    'Evolving Pokémon': 'evolving',
    'Fishing': 'fishing',
    'Exploring & Claiming Land': 'exploring',
    'Mining & Gathering': 'mining',
    'Making Money': 'economy',
    'Automatic Income': 'automation',
    'Collecting & Cosmetics': 'cosmetics',
    'Social & Clans': 'social',
    'Daily Rewards & Playtime': 'daily'
}

def parse_rewards(reward_str):
    rewards = []
    parts = [p.strip() for p in reward_str.split(',') if p.strip()]
    for p in parts:
        m_curr = re.search(r'([\d,]+)\s*(?:Pok[ée]Coins|Pokecoins)', p, re.I)
        if m_curr:
            amt = int(m_curr.group(1).replace(',', ''))
            rewards.append({'type': 'CURRENCY', 'currency': 'POKECOIN', 'amount': amt})
            continue
        m_raid = re.search(r'([\d,]+)\s*Raid Coins', p, re.I)
        if m_raid:
            amt = int(m_raid.group(1).replace(',', ''))
            rewards.append({'type': 'CURRENCY', 'currency': 'RAID_COIN', 'amount': amt})
            continue
        m_key = re.search(r'(\d+)x?\s*(.+?)\s*Crate Key', p, re.I)
        if m_key:
            amt = int(m_key.group(1))
            key_name = m_key.group(2).strip().lower().replace(' ', '_')
            rewards.append({'type': 'CRATE_KEY', 'key_id': key_name, 'amount': amt})
            continue
        m_item = re.search(r'(\d+)x?\s*(.+)', p)
        if m_item:
            amt = int(m_item.group(1))
            item_name = m_item.group(2).strip()
            rewards.append({'type': 'ITEM', 'id': slugify(item_name), 'display': item_name, 'amount': amt})
            continue
        rewards.append({'type': 'COMMAND', 'command': f'give-reward {p}', 'display': p})
    return rewards

sys_digest = {}
digest_file = r'C:\Users\thamm\Downloads\pixelclub-handoff-20261014\pixelclub-handoff-20261014\KrausVault-20261014\KrausVault\Pixelclub\port\digest-achievement-hooks.md'
if os.path.exists(digest_file):
    with open(digest_file, 'r', encoding='utf-8') as f:
        for line in f:
            if '| **' in line and '|' in line:
                cols = [c.strip() for c in line.split('|')]
                if len(cols) >= 5 and cols[2].startswith('**'):
                    ach_n = cols[2].replace('**', '').strip()
                    bucket = cols[3].replace('`', '').strip()
                    trig = cols[4].replace('`', '').strip()
                    sys_digest[ach_n] = (bucket, trig)

final_categories = []
cat_idx = 1
for cat_name, cat_data in data['categories'].items():
    c_slug = cat_slugs.get(cat_name, slugify(cat_name))
    cat_entry = {
        'id': c_slug,
        'index': cat_idx,
        'name': cat_name,
        'description': cat_data.get('description', ''),
        'achievements': []
    }
    cat_idx += 1
    
    for sec_name, sec_achs in cat_data.get('sections', {}).items():
        for ach_name, ach_info in sec_achs.items():
            a_slug = slugify(ach_name)
            unique_id = f'{c_slug}_{a_slug}'
            
            tgt_str = str(ach_info.get('target', '1')).replace('0/', '')
            if 'k' in tgt_str.lower():
                tgt_num = int(float(tgt_str.lower().replace('k', '')) * 1000)
            elif 'm' in tgt_str.lower():
                tgt_num = int(float(tgt_str.lower().replace('m', '')) * 1000000)
            else:
                try:
                    tgt_num = int(tgt_str)
                except ValueError:
                    tgt_num = 1
            
            raw_rew = ach_info.get('proposed_reward', '')
            parsed_rews = parse_rewards(raw_rew)
            hook_bucket, hook_trig = sys_digest.get(ach_name, ('BUILT_IN_HOOK', 'StandardEvent'))
            
            ach_obj = {
                'id': unique_id,
                'category_id': c_slug,
                'category_name': cat_name,
                'section': sec_name,
                'name': ach_name,
                'description': ach_info.get('description', ''),
                'target': tgt_num,
                'trigger_bucket': hook_bucket,
                'trigger_type': hook_trig,
                'rewards': parsed_rews,
                'reward_display': raw_rew
            }
            cat_entry['achievements'].append(ach_obj)
            
    final_categories.append(cat_entry)

dataset = {
    'version': '1.0.0',
    'total_categories': len(final_categories),
    'total_achievements': sum(len(c['achievements']) for c in final_categories),
    'categories': final_categories
}

out_file = r'src/main/resources/data/pxc-achievements/achievements.json'
with open(out_file, 'w', encoding='utf-8') as f:
    json.dump(dataset, f, indent=2, ensure_ascii=False)

print(f"Successfully wrote {dataset['total_achievements']} achievements across {dataset['total_categories']} categories to {out_file}")
