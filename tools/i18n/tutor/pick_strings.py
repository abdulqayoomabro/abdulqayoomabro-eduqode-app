import re,sys,json
s=open(sys.argv[1]).read()
keep=('templates/','views/quiz','views/qna','views/fragments','classes/Utils.php','classes/Quiz.php','classes/Course.php','classes/Lesson.php','classes/Student.php','includes/translate-text.php','classes/Dashboard.php','classes/Q_and_A.php')
skip=('templates/ecommerce','templates/dashboard/settings/withdraw','templates/dashboard/withdraw')
out=[]
for e in s.split('\n\n'):
    refs=re.findall(r'#: (\S+?):\d+',e)
    if not any(r.startswith(keep) and not r.startswith(skip) for r in refs): continue
    def get(tag):
        m=re.search(r'^'+tag+r' ((?:".*"\n?)+)',e,re.M)
        if not m: return None
        return ''.join(json.loads(x) for x in re.findall(r'"(?:[^"\\]|\\.)*"',m.group(1)))
    ctx=get('msgctxt'); mid=get('msgid'); pl=get('msgid_plural')
    if not mid: continue
    out.append({'ctx':ctx,'id':mid,'plural':pl})
json.dump(out,open(sys.argv[2],'w'),ensure_ascii=False,indent=0)
print(len(out), sum(len(o['id'].split()) for o in out),'words')
