"""Builds the app's Urdu files from app_ur.py:
   app/src/main/assets/i18n/ur.json        (text in Kotlin, see I18n.kt)
   app/src/main/res/values-ur/strings.xml  (layouts and menus)
Fails when a text has no Urdu or the %s placeholders do not match."""
import json, re, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from app_ur import UR, RES
from app_keys import keys

def specs(s):
    s = s.replace('%%', '')
    pos = re.findall(r'%(\d+)\$[sd]', s)
    return len(set(pos)) if pos else len(re.findall(r'%[sd]', s))

errors = []
needed = keys() + ['Phone language']
for k in needed:
    if k not in UR: errors.append('missing Urdu: ' + repr(k))
    elif specs(k) != specs(UR[k]): errors.append('placeholders differ: ' + repr(k))
res_en = dict(re.findall(r'<string name="([^"]+)">(.*?)</string>', open('app/src/main/res/values/strings.xml', encoding='utf-8').read()))
for k in res_en:
    if k != 'app_name' and k not in RES: errors.append('missing Urdu resource: ' + k)
if errors:
    print('\n'.join(errors)); sys.exit(1)

os.makedirs('app/src/main/assets/i18n', exist_ok=True)
with open('app/src/main/assets/i18n/ur.json', 'w', encoding='utf-8') as f:
    json.dump({k: UR[k] for k in needed}, f, ensure_ascii=False, indent=0, sort_keys=True)
os.makedirs('app/src/main/res/values-ur', exist_ok=True)
def esc(v): return v.replace('&', '&amp;').replace('<', '&lt;').replace("'", "\\'").replace('"', '\\"')
with open('app/src/main/res/values-ur/strings.xml', 'w', encoding='utf-8') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- Built by tools/i18n/build_app.py from tools/i18n/app_ur.py. -->\n<resources>\n')
    for k in res_en:
        if k in RES: f.write(f'    <string name="{k}">{esc(RES[k])}</string>\n')
    f.write('</resources>\n')
unused = sorted(set(UR) - set(needed))
print(f'ok: {len(needed)} texts, {len(RES)} resources' + (f'; unused: {unused}' if unused else ''))
