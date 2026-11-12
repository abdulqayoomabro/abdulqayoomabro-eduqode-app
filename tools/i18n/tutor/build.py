"""Builds Urdu for Tutor LMS's student and teacher screens:
   wordpress/eduqode-app-connector/languages/tutor/tutor-ur.po (+ .mo with wp-cli)
strings.json: Tutor LMS (free) texts of the front end, made with pick_strings.py from `wp i18n make-pot`.
b1..b4.py: the Urdu, in the same order."""
import json, os, re, subprocess, sys
here = os.path.dirname(os.path.abspath(__file__))
strings = json.load(open(os.path.join(here, 'strings.json'), encoding='utf-8'))
ur = []
for b in ('b1', 'b2', 'b3', 'b4'):
    ns = {}
    exec(open(os.path.join(here, b + '.py'), encoding='utf-8').read(), ns)
    ur += ns['T']
if len(ur) != len(strings):
    sys.exit(f'{len(strings)} texts but {len(ur)} translations')

def specs(s):
    s = s.replace('%%', '')
    return sorted(set(re.findall(r'%(?:\d+\$)?[sd]', s)) - {'%s', '%d'}) or len(re.findall(r'%[sd]', s))
def tags(s): return sorted(re.findall(r'<[^>]+>', s))
def q(s): return json.dumps(s, ensure_ascii=False)
errors = []
out = ['msgid ""', 'msgstr ""', '"Content-Type: text/plain; charset=UTF-8\\n"', '"Language: ur\\n"',
       '"Plural-Forms: nplurals=2; plural=(n != 1);\\n"', '"X-Domain: tutor\\n"', '']
for o, t in zip(strings, ur):
    plural = o['plural'] is not None
    if plural != isinstance(t, tuple):
        errors.append('plural mismatch: ' + o['id']); continue
    for src, dst in ([(o['id'], t[0]), (o['plural'], t[1])] if plural else [(o['id'], t)]):
        if specs(src) != specs(dst) and not (plural and src == o['id'] and specs(o['plural']) == specs(dst)):
            errors.append(f'placeholders: {src!r} -> {dst!r}')
        if tags(src) != tags(dst):
            errors.append(f'html: {src!r}')
    if o['ctx']: out.append('msgctxt ' + q(o['ctx']))
    out.append('msgid ' + q(o['id']))
    if plural:
        out += ['msgid_plural ' + q(o['plural']), 'msgstr[0] ' + q(t[0]), 'msgstr[1] ' + q(t[1])]
    else:
        out.append('msgstr ' + q(t))
    out.append('')
if errors:
    print('\n'.join(errors)); sys.exit(1)
dest = os.path.join(here, '../../../wordpress/eduqode-app-connector/languages/tutor')
os.makedirs(dest, exist_ok=True)
po = os.path.join(dest, 'tutor-ur.po')
open(po, 'w', encoding='utf-8').write('\n'.join(out))
print(f'ok: {len(strings)} Tutor LMS texts -> {os.path.normpath(po)}')
if len(sys.argv) > 1:   # path to wp-cli.phar: also build the .mo
    subprocess.run(['php', sys.argv[1], '--allow-root', 'i18n', 'make-mo', po, dest], check=True)
