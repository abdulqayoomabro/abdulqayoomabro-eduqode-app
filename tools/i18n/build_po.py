"""Builds languages/<domain>-ur.po from the code and the Urdu dictionaries, and checks nothing is missing.
   python3 tools/i18n/build_po.py   (then: wp i18n make-mo <languages dir>)"""
import re, glob, os, sys, importlib.util
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
def load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, 'tools/i18n', name + '.py'))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m.UR
def msgids(plugin_dir, fn):
    out = []
    for f in sorted(glob.glob(plugin_dir + '/*.php') + glob.glob(plugin_dir + '/includes/*.php') + glob.glob(plugin_dir + '/templates/*.php')):
        for m in re.finditer(fn + r"\( '((?:[^'\\]|\\.)*)' \)", open(f, encoding='utf-8').read()):
            t = m.group(1).replace("\\'", "'")
            if t not in out: out.append(t)
    return out
def esc(s): return s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
def build(plugin, domain, fn, dict_name):
    d = os.path.join(ROOT, 'wordpress', plugin)
    ur = load(dict_name); ids = msgids(d, fn)
    missing = [i for i in ids if i not in ur]
    if missing:
        print(plugin, 'MISSING:', missing); sys.exit(1)
    os.makedirs(d + '/languages', exist_ok=True)
    with open(f'{d}/languages/{domain}-ur.po', 'w', encoding='utf-8') as f:
        f.write('msgid ""\nmsgstr ""\n"Content-Type: text/plain; charset=UTF-8\\n"\n"Language: ur\\n"\n"Plural-Forms: nplurals=2; plural=(n != 1);\\n"\n\n')
        for i in ids: f.write(f'msgid "{esc(i)}"\nmsgstr "{esc(ur[i])}"\n\n')
    print(plugin, len(ids), 'strings')
if __name__ == '__main__':
    build('eduqode-school', 'eduqode-school', 'eqs__', 'school_ur')
    if os.path.exists(os.path.join(ROOT, 'tools/i18n/connector_ur.py')):
        build('eduqode-app-connector', 'eduqode-app-connector', 'eqa__', 'connector_ur')
