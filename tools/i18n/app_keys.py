"""Lists the English texts the app passes to tr() (keys of app/src/main/assets/i18n/ur.json)."""
import re, glob
def keys():
    out = []
    for f in sorted(glob.glob('app/src/main/java/**/*.kt', recursive=True)):
        s = open(f, encoding='utf-8').read()
        for m in re.finditer(r'(?<![A-Za-z0-9_.])tr\("((?:[^"\\]|\\.)*)"', s):
            k = m.group(1).replace('\\n', '\n').replace('\\"', '"').replace('\\$', '$').replace('\\\\', '\\')
            if k not in out: out.append(k)
    return out
if __name__ == '__main__':
    for k in keys(): print(repr(k))
