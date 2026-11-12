#!/bin/bash
# End-to-end test of the EduQode App Connector against a real WordPress + Tutor LMS.
B=http://127.0.0.1:8099
UA="Mozilla/5.0 (Linux; Android 14) EduQodeApp/1.0"
J=jar.txt; rm -f $J
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (expected: $3, got: $2)"; fail=$((fail+1)); fi; }
api() { curl -s -A "$UA" -H "X-EduQode-Token: $TOKEN" "$@"; }
ids=$(cat ids.json); id() { echo "$ids" | python3 -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }
C=$(id course); L1=$(id l1); L2=$(id l2); L3=$(id l3); Q=$(id q); OTHER=$(id other); OL=$(id ol)

# Log in like the web view does (cookies)
curl -s -c $J -b "wordpress_test_cookie=WP%20Cookie%20check" -A "$UA" -o /dev/null \
  -d "log=student&pwd=student&wp-submit=Log+In&testcookie=1" $B/wp-login.php
code=$(curl -s -o /dev/null -w "%{http_code}" -A "$UA" "$B/?eduqode_app=issue_token")
check "token: GET is refused" "$code" "405"
code=$(curl -s -o /dev/null -w "%{http_code}" -A "$UA" -X POST "$B/?eduqode_app=issue_token")
check "token: not logged in is refused" "$code" "401"
resp=$(curl -s -b $J -A "$UA" -X POST -d "device_id=dev-1&device_name=Pixel" "$B/?eduqode_app=issue_token")
TOKEN=$(echo "$resp" | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
check "token: issued for logged-in student" "$(echo "$resp" | python3 -c "import json,sys;print(json.load(sys.stdin)['user']['name'])")" "Ali Student"

code=$(curl -s -o /dev/null -w "%{http_code}" "$B/?rest_route=/eduqode/v1/me")
check "REST without token is 401" "$code" "401"
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-EduQode-Token: 2.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" "$B/?rest_route=/eduqode/v1/me")
check "REST with fake token is 401" "$code" "401"
check "me returns the student" "$(api "$B/?rest_route=/eduqode/v1/me" | python3 -c "import json,sys;print(json.load(sys.stdin)['name'])")" "Ali Student"
check "no-store cache header" "$(api -D - -o /dev/null "$B/?rest_route=/eduqode/v1/me" | grep -i '^cache-control' | grep -c no-store)" "1"

courses=$(api "$B/?rest_route=/eduqode/v1/courses")
HCID=$( [ -f ids_h5p.json ] && python3 -c "import json;print(json.load(open('ids_h5p.json'))['h5p_course'])" || echo 0 )
check "courses lists only enrolled courses" "$(echo "$courses" | python3 -c "import json,sys;print(sorted(c['id'] for c in json.load(sys.stdin)['courses'] if c['id'] != $HCID))")" "[$C]"
check "not-enrolled course not listed" "$(echo "$courses" | python3 -c "import json,sys;print($OTHER in [c['id'] for c in json.load(sys.stdin)['courses']])")" "False"
check "course lesson_count" "$(echo "$courses" | python3 -c "import json,sys;print([c for c in json.load(sys.stdin)['courses'] if c['id']==$C][0]['lesson_count'])")" "3"

code=$(api -o /dev/null -w "%{http_code}" "$B/?rest_route=/eduqode/v1/courses/$OTHER/package")
check "package of a non-enrolled course is 403" "$code" "403"

api "$B/?rest_route=/eduqode/v1/courses/$C/package" > package.json
python3 - "$L1" "$L2" "$L3" "$Q" > pkg_checks.txt <<'PY'
import json,sys
L1,L2,L3,Q=map(int,sys.argv[1:])
p=json.load(open('package.json'))
items={i['id']:i for t in p['topics'] for i in t['items']}
out=[]
out.append(("topics in order", [t['title'] for t in p['topics']], ['Getting started','Next steps']))
out.append(("items in order", [i['title'] for i in p['topics'][0]['items']], ['Intro (YouTube)','Install (own mp4)','Quiz 1']))
out.append(("YouTube online source", items[L1]['video']['online']['provider'], 'youtube'))
out.append(("test-mode video on listed lesson", (items[L1]['video']['offline'] or {}).get('origin'), 'test_mode'))
out.append(("html5 mp4 is offline", (items[L2]['video']['offline'] or {}).get('origin'), 'tutor_html5'))
out.append(("html5 mp4 size known", (items[L2]['video']['offline'] or {}).get('size_bytes'), 2048))
out.append(("admin offline link used", (items[L3]['video']['offline'] or {}).get('url'), 'https://cdn.example.com/vars.mp4'))
out.append(("quiz is online only", (items[Q]['type'], items[Q]['online_only']), ('quiz', True)))
out.append(("PDF attachment listed", [a['mime'] for a in items[L1]['attachments']], ['application/pdf']))
out.append(("image extracted", len(items[L1]['images']), 1))
out.append(("srcset removed", 'srcset' in items[L1]['content_html'], False))
out.append(("not completed yet", items[L1]['completed_at'], 0))
for name,got,exp in out:
    print(("PASS  " if got==exp else "FAIL  ")+name+("" if got==exp else f"  (expected: {exp}, got: {got})"))
PY
cat pkg_checks.txt; pass=$((pass+$(grep -c ^PASS pkg_checks.txt))); fail=$((fail+$(grep -c ^FAIL pkg_checks.txt)))

# Sync
now=$(($(date +%s)*1000))
body='{"events":[
 {"id":"e1","type":"lesson_complete","course_id":'$C',"lesson_id":'$L1',"client_time":'$((now-60000))'},
 {"id":"e2","type":"video_position","course_id":'$C',"lesson_id":'$L2',"client_time":'$((now-50000))',"position_ms":90000,"duration_ms":120000},
 {"id":"e3","type":"lesson_access","course_id":'$C',"lesson_id":'$L2',"client_time":'$((now-40000))'},
 {"id":"e4","type":"lesson_complete","course_id":'$OTHER',"lesson_id":'$OL',"client_time":'$now'},
 {"id":"e5","type":"lesson_complete","course_id":'$C',"lesson_id":'$Q',"client_time":'$now'},
 {"id":"e6","type":"lesson_complete","course_id":'$C',"lesson_id":'$OL',"client_time":'$now'}]}'
r=$(api -X POST -H "Content-Type: application/json" -d "$body" "$B/?rest_route=/eduqode/v1/sync")
st() { echo "$r" | python3 -c "import json,sys;d=json.load(sys.stdin);print({x['id']:x['status']+('/'+x.get('reason','') if x.get('reason') else '') for x in d['results']}['$1'])"; }
check "sync: offline completion applied" "$(st e1)" "applied"
check "sync: video position applied" "$(st e2)" "applied"
check "sync: last lesson applied" "$(st e3)" "applied"
check "sync: non-enrolled course rejected" "$(st e4)" "rejected/no_access"
check "sync: quiz cannot be completed offline" "$(st e5)" "rejected/not_a_lesson"
check "sync: lesson from another course rejected" "$(st e6)" "rejected/not_in_course"
check "sync: progress percent = Tutor's own (1 of 3 lessons + 4 quizzes + 1 assignment)" "$(echo "$r" | python3 -c "import json,sys;print(json.load(sys.stdin)['progress'][0]['percent'])")" "13"

r=$(api -X POST -H "Content-Type: application/json" -d "$body" "$B/?rest_route=/eduqode/v1/sync")
check "sync: same events again are duplicates" "$(st e1),$(st e2)" "duplicate,duplicate"

old='{"events":[{"id":"e7","type":"video_position","course_id":'$C',"lesson_id":'$L2',"client_time":'$((now-90000))',"position_ms":10000,"duration_ms":120000},
 {"id":"e8","type":"lesson_complete","course_id":'$C',"lesson_id":'$L1',"client_time":'$((now-90000))'}]}'
r=$(api -X POST -H "Content-Type: application/json" -d "$old" "$B/?rest_route=/eduqode/v1/sync")
check "sync: older position from 2nd phone does not overwrite" "$(st e7)" "stale/newer_on_server"
check "sync: completing again keeps completion" "$(st e8)" "already"

prog=$(api "$B/?rest_route=/eduqode/v1/courses/$C/progress")
check "progress: position kept at newest" "$(echo "$prog" | python3 -c "import json,sys;print(json.load(sys.stdin)['positions']['$L2']['position_ms'])")" "90000"
check "progress: completed lesson listed" "$(echo "$prog" | python3 -c "import json,sys;print(list(json.load(sys.stdin)['completed'].keys()))")" "['$L1']"
check "progress: last lesson" "$(echo "$prog" | python3 -c "import json,sys;print(json.load(sys.stdin)['last_lesson']['lesson_id'])")" "$L2"

r=$(api -X POST -H "Content-Type: application/json" -d '{"events":[],"course_ids":['$C','$OTHER']}' "$B/?rest_route=/eduqode/v1/sync")
check "pull: enrolled course progress + no_access list" "$(echo "$r" | python3 -c "import json,sys;d=json.load(sys.stdin);print(len(d['progress']),d.get('no_access'))")" "1 [$OTHER]"

# ---- Offline quizzes ----
WPC="php ../wp/wp-cli.phar --allow-root --path=../wp/site"
QZ=$(echo "$ids" | python3 -c "import json,sys;print(json.dumps(json.load(sys.stdin)['quizzes']))")
QUIZ2=$(echo "$QZ" | python3 -c "import json,sys;print(json.load(sys.stdin)['quiz2'])")
QUIZ3=$(echo "$QZ" | python3 -c "import json,sys;print(json.load(sys.stdin)['quiz3'])")
QUIZ4=$(echo "$QZ" | python3 -c "import json,sys;print(json.load(sys.stdin)['quiz4'])")
api "$B/?rest_route=/eduqode/v1/courses/$C/package" > package2.json
python3 - "$QUIZ2" "$QUIZ3" "$QUIZ4" > quiz_pkg_checks.txt <<'PY'
import json,sys
q2,q3,q4=map(int,sys.argv[1:])
p=json.load(open('package2.json'))
items={i['id']:i for t in p['topics'] for i in t['items']}
z=items[q2].get('quiz') or {}
out=[]
out.append(("quiz with supported types is offline", items[q2]['online_only'], False))
out.append(("quiz question types in order", [q['type'] for q in z.get('questions',[])], ['true_false','single_choice','multiple_choice','fill_in_the_blank','ordering','matching','image_answering','short_answer']))
out.append(("quiz settings: 10 min, unlimited, pass 50%", (z.get('time_limit_seconds'), z.get('attempts_allowed'), z.get('passing_grade')), (600, 0, 50.0)))
out.append(("correct answers included (instant result)", [a['is_correct'] for a in z['questions'][1]['answers']], [False, True, False]))
out.append(("fill-in-the-blank answers included", z['questions'][3]['answers'][0]['match'], 'easy|fun'))
out.append(("image of image-answering question listed", len(items[q2].get('images',[])), 1))
out.append(("one-attempt quiz allows 1", items[q3]['quiz']['attempts_allowed'], 1))
out.append(("quiz with an H5P question stays online", (items[q4]['online_only'], 'quiz' in items[q4]), (True, False)))
for name,got,exp in out:
    print(("PASS  " if got==exp else "FAIL  ")+name+("" if got==exp else f"  (expected: {exp}, got: {got})"))
PY
cat quiz_pkg_checks.txt; pass=$((pass+$(grep -c ^PASS quiz_pkg_checks.txt))); fail=$((fail+$(grep -c ^FAIL quiz_pkg_checks.txt)))

# Correct answers in Tutor's own format, built from the package.
ATTEMPT=$(python3 - "$QUIZ2" "$C" "$now" <<'PY'
import json,sys
q2,c,now=int(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3])
p=json.load(open('package2.json'))
z=[i for t in p['topics'] for i in t['items'] if i['id']==q2][0]['quiz']
ans={}
for q in z['questions']:
    A=q['answers']; t=q['type']
    if t in ('true_false','single_choice'): ans[q['id']]=[a['id'] for a in A if a['is_correct']][0]
    elif t=='multiple_choice': ans[q['id']]=[a['id'] for a in A if a['is_correct']]
    elif t=='fill_in_the_blank': ans[q['id']]=['Easy',' fun ']
    elif t in ('ordering','matching','image_matching'): ans[q['id']]={'answers':[a['id'] for a in sorted(A,key=lambda a:a['order'])]}
    elif t=='image_answering': ans[q['id']]={'answer_id':{str(a['id']):'lahore' for a in A}}
    else: ans[q['id']]='Because it is readable.'
ev={'id':'quiz-1','type':'quiz_attempt','course_id':c,'lesson_id':q2,'quiz_id':q2,'started_at':now-120000,'client_time':now-60000,
    'question_ids':[q['id'] for q in z['questions']],'answers':ans}
print(json.dumps({'events':[ev]}))
PY
)
r=$(api -X POST -H "Content-Type: application/json" -d "$ATTEMPT" "$B/?rest_route=/eduqode/v1/sync")
att() { echo "$r" | python3 -c "import json,sys;d=json.load(sys.stdin);a=[x for x in d['results'] if x['id']=='$1'][0];print(a['status']+'|'+a.get('reason','')+'|'+json.dumps(a.get('attempt')))"; }
IFS='|' read -r ST RS AJ <<< "$(att quiz-1)"
check "quiz attempt recorded through Tutor LMS" "$ST" "applied"
check "Tutor graded: 8 of 10 marks (short answer waits for teacher)" "$(echo "$AJ" | python3 -c "import json,sys;a=json.loads(sys.stdin.read());print(float(a['earned']), float(a['total']))")" "8.0 10.0"
check "attempt marked for teacher review" "$(echo "$AJ" | python3 -c "import json,sys;print(json.loads(sys.stdin.read())['status'])")" "review_required"
r=$(api -X POST -H "Content-Type: application/json" -d "$ATTEMPT" "$B/?rest_route=/eduqode/v1/sync")
check "same quiz attempt sent again is not saved twice" "$(att quiz-1 | cut -d'|' -f1)" "duplicate"
check "Tutor has exactly 1 attempt and 8 answers saved" "$($WPC eval 'global $wpdb; echo $wpdb->get_var("SELECT COUNT(*) FROM {$wpdb->prefix}tutor_quiz_attempts WHERE quiz_id='$QUIZ2'"), " ", $wpdb->get_var("SELECT COUNT(*) FROM {$wpdb->prefix}tutor_quiz_attempt_answers WHERE quiz_id='$QUIZ2'");' 2>/dev/null)" "1 8"
check "offline start/end times kept" "$($WPC eval 'global $wpdb; $a=$wpdb->get_row("SELECT attempt_started_at s, attempt_ended_at e FROM {$wpdb->prefix}tutor_quiz_attempts WHERE quiz_id='$QUIZ2'"); echo strtotime($a->e)-strtotime($a->s);' 2>/dev/null)" "60"

Q3ID=$(echo "$QZ" | python3 -c "import json,sys;print(json.load(sys.stdin)['Q3'][0])")
Q3WRONG=$(echo "$QZ" | python3 -c "import json,sys;print(json.load(sys.stdin)['Q3'][1][1])")
one='{"events":[{"id":"q3-a","type":"quiz_attempt","course_id":'$C',"quiz_id":'$QUIZ3',"started_at":'$((now-5000))',"client_time":'$now',"question_ids":['$Q3ID'],"answers":{"'$Q3ID'":'$Q3WRONG'}},
 {"id":"q3-b","type":"quiz_attempt","course_id":'$C',"quiz_id":'$QUIZ3',"started_at":'$((now-4000))',"client_time":'$((now+1))',"question_ids":['$Q3ID'],"answers":{"'$Q3ID'":'$Q3WRONG'}}]}'
r=$(api -X POST -H "Content-Type: application/json" -d "$one" "$B/?rest_route=/eduqode/v1/sync")
check "wrong answer graded 0 and failed" "$(att q3-a | python3 -c "import json,sys;s=sys.stdin.read().split('|',2);a=json.loads(s[2]);print(s[0],float(a['earned']),a['result'])")" "applied 0.0 fail"
check "attempt limit respected (second attempt refused)" "$(att q3-b | cut -d'|' -f1,2)" "rejected|attempts_exceeded"
prog=$(api "$B/?rest_route=/eduqode/v1/courses/$C/progress")
check "progress lists quiz attempts" "$(echo "$prog" | python3 -c "import json,sys;d=json.load(sys.stdin)['quizzes'];print(d['$QUIZ2']['attempts_used'], d['$QUIZ3']['attempts_used'])")" "1 1"

# ---- Assignments and app settings ----
ASG=$(echo "$ids" | python3 -c "import json,sys;print(json.load(sys.stdin)['asg'])")
check "assignment readable offline with its dataset" "$(python3 -c "
import json; p=json.load(open('package2.json')); a=[i for t in p['topics'] for i in t['items'] if i['id']==$ASG][0]
print(a['type'], a['online_only'], 'ASSIGNMENT-MARKER' in a['content_html'], [x['mime'] for x in a['attachments']], float(a['assignment']['total_mark']), a['assignment']['submitted'])")" "assignment False True ['text/csv'] 10.0 False"
cfg=$(curl -s "$B/?rest_route=/eduqode/v1/app-config")
check "app settings served without login" "$(echo "$cfg" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['config']['googleLogin'], '/cart' in d['config']['blockedPathParts'], 'in-app' in d['css'])")" "off True True"

# Tutor LMS Pro-style rules
WPR="php ../wp/wp-cli.phar --allow-root --path=../wp/site"
$WPR option update eduqode_test_block_lesson $L3 >/dev/null
r=$(api -X POST -H "Content-Type: application/json" -d '{"events":[{"id":"r1","type":"lesson_complete","course_id":'$C',"lesson_id":'$L3',"client_time":'$now'}]}' "$B/?rest_route=/eduqode/v1/sync")
check "rule: completion refused by a Tutor rule is reported" "$(st r1)" "rejected/validation"
$WPR option update eduqode_test_block_lesson 0 >/dev/null
$WPR option update eduqode_test_hide_lesson $L3 >/dev/null
check "rule: lesson left out of the offline pack by filter" "$(api "$B/?rest_route=/eduqode/v1/courses/$C/package" | python3 -c "import json,sys;print($L3 in [i['id'] for t in json.load(sys.stdin)['topics'] for i in t['items']])")" "False"
$WPR option update eduqode_test_hide_lesson 0 >/dev/null
$WPR option update eduqode_test_drip_lesson $L3 >/dev/null
check "content drip: lesson not released yet is left out" "$(api "$B/?rest_route=/eduqode/v1/courses/$C/package" | python3 -c "import json,sys;print($L3 in [i['id'] for t in json.load(sys.stdin)['topics'] for i in t['items']])")" "False"
$WPR option update eduqode_test_drip_lesson 0 >/dev/null

# Website (Tutor LMS itself) sees the offline completion, and Tutor's redirect hook is restored
WPC="php ../wp/wp-cli.phar --allow-root --path=../wp/site"
check "website: Tutor marks lesson completed" "$($WPC eval 'echo tutor_utils()->is_completed_lesson('$L1', 2) ? "yes" : "no";' 2>/dev/null)" "yes"
check "website: Tutor best watched time updated (s)" "$($WPC eval 'echo (int) tutor_utils()->get_lesson_reading_info('$L2', 2, "video_best_watched_time");' 2>/dev/null)" "90"
check "website: enrollment intact" "$($WPC eval 'echo tutor_utils()->is_enrolled('$C', 2) ? "yes" : "no";' 2>/dev/null)" "yes"
check "website: Tutor redirect hook restored after sync" "$($WPC eval 'global $wp_filter; $n=0; foreach (($wp_filter["tutor_lesson_completed_after"]->callbacks ?? []) as $cbs) foreach ($cbs as $cb) if (is_array($cb["function"]) && $cb["function"][1]==="tutor_lesson_completed_after") $n++; echo $n;' 2>/dev/null)" "1"

# ---------------- H5P (offline exercises in lessons and H5P quizzes) ----------------
if [ -f ids_h5p.json ]; then
hid() { python3 -c "import json;print(json.load(open('ids_h5p.json'))['$1'])"; }
HC=$(hid h5p_course); HL=$(hid h5p_lesson); HQ=$(hid h5p_quiz); HQQ=$(hid h5p_question); HID=$(hid h5p_content)
$WPR eval '$o = get_option("tutor_option", []); $o["disable_complete_lesson_button"] = 1; update_option("tutor_option", $o);' >/dev/null
api "$B/?rest_route=/eduqode/v1/courses/$HC/package" > h5p_package.json
python3 - "$HL" "$HQ" "$HID" > h5p_checks.txt <<'PY'
import json,sys,urllib.request
hl,hq,hid=map(int,sys.argv[1:])
p=json.load(open('h5p_package.json'))
items={i['id']:i for t in p['topics'] for i in t['items']}
L=items[hl]; c=(L.get('h5p') or [{}])[0]
libs=p.get('h5p_libraries',{})
out=[]
out.append(("h5p: lesson lists its exercise", (c.get('id'), c.get('library')), (hid, 'H5P.TrueFalse')))
out.append(("h5p: lesson text kept, shortcode removed", ('H5P-LESSON-TEXT' in L['content_html'], '[h5p' in L['content_html']), (True, False)))
out.append(("h5p: h5p.json main library + dependencies", (c['h5p_json']['mainLibrary'], 'H5P.Question' in [d['machineName'] for d in c['h5p_json']['preloadedDependencies']]), ('H5P.TrueFalse', True)))
out.append(("h5p: content.json from H5P plugin", json.loads(c['content_json']).get('correct'), 'false'))
out.append(("h5p: every library folder sent once", sorted(libs.keys()) == sorted(set(c['libraries'])), True))
files=[f for v in libs.values() for f in v]
out.append(("h5p: library.json of main library included", any(f['path']=='H5P.TrueFalse-1.6/library.json' for f in files), True))
out.append(("h5p: no editor/translation/server files", [f['path'] for f in files if '/language/' in f['path'] or f['path'].endswith(('.php','semantics.json'))], []))
f=[f for f in files if f['path']=='H5P.TrueFalse-1.6/library.json'][0]
data=urllib.request.urlopen(f['url']).read()
out.append(("h5p: library file downloadable with the listed size", len(data), f['size']))
out.append(("h5p: completion rule sent to app", p.get('h5p_lesson_gate'), True))
Q=items[hq]
out.append(("h5p quiz: offline with H5P question", (Q['online_only'], [q['type'] for q in Q['quiz']['questions']], Q['quiz']['questions'][0]['h5p']['id']), (False, ['h5p'], hid)))
for name,got,exp in out:
    print(("PASS  " if got==exp else "FAIL  ")+name+("" if got==exp else f"  (expected: {exp}, got: {got})"))
PY
cat h5p_checks.txt; pass=$((pass+$(grep -c ^PASS h5p_checks.txt))); fail=$((fail+$(grep -c ^FAIL h5p_checks.txt)))

now=$(($(date +%s)*1000))
ST='{"actor":{"name":"Ali","objectType":"Agent"},"verb":{"id":"http://adlnet.gov/expapi/verbs/answered","display":{"en-US":"answered"}},"object":{"id":"https://app/h5p/'$HID'","objectType":"Activity","definition":{"extensions":{"http://h5p.org/x-api/h5p-local-content-id":'$HID'},"name":{"en-US":"Is the sky blue?"},"interactionType":"true-false","correctResponsesPattern":["false"]}},"result":{"score":{"min":0,"max":1,"raw":1,"scaled":1},"completion":true,"success":true,"duration":"PT3S","response":"false"}}'
STJ=$(python3 -c "import json,sys;print(json.dumps(sys.argv[1]))" "$ST")
hsync() { api -X POST -H "Content-Type: application/json" -d "$1" "$B/?rest_route=/eduqode/v1/sync"; }
hst() { python3 -c "import json,sys;r=json.load(sys.stdin);x=[e for e in r['results'] if e['id']=='$1'][0];print(x['status']+('/'+x['reason'] if x.get('reason') else ''))"; }
r=$(hsync '{"events":[{"id":"hc0","type":"lesson_complete","course_id":'$HC',"lesson_id":'$HL',"client_time":'$((now-9000))'}]}')
check "h5p: lesson cannot be completed before its exercise (Tutor Pro rule)" "$(echo "$r" | hst hc0)" "rejected/validation"
r=$(hsync '{"events":[{"id":"hs1","type":"h5p_statement","course_id":'$HC',"lesson_id":'$HL',"content_id":'$HID',"client_time":'$((now-8000))',"opened_at":'$((now-60000))',"statement":'"$STJ"'},{"id":"hs2","type":"h5p_statement","course_id":'$HC',"lesson_id":'$HL',"content_id":999,"client_time":'$((now-7900))',"statement":'"$STJ"'},{"id":"hf1","type":"h5p_lesson_finished","course_id":'$HC',"lesson_id":'$HL',"client_time":'$((now-7000))'},{"id":"hc1","type":"lesson_complete","course_id":'$HC',"lesson_id":'$HL',"client_time":'$((now-6000))'}]}')
check "h5p: offline answer stored" "$(echo "$r" | hst hs1)" "applied"
check "h5p: exercise not in the lesson refused" "$(echo "$r" | hst hs2)" "rejected/not_in_lesson"
check "h5p: lesson finished recorded" "$(echo "$r" | hst hf1)" "applied"
check "h5p: lesson completes after its exercise" "$(echo "$r" | hst hc1)" "applied"
check "h5p: Tutor marks the H5P lesson completed" "$($WPC eval 'echo tutor_utils()->is_completed_lesson('$HL', '$(id student)') ? "yes" : "no";' 2>/dev/null)" "yes"
check "h5p: Tutor Pro statement tables filled" "$($WPC eval 'global $wpdb; $p=$wpdb->prefix; echo $wpdb->get_var("select count(*) from {$p}tutor_h5p_statement"), ",", $wpdb->get_var("select count(*) from {$p}tutor_h5p_lesson_statement where lesson_id='$HL' and verb=\"answered\" and result_raw_score=1");' 2>/dev/null)" "1,1"
check "h5p: H5P plugin result saved (score/max)" "$($WPC eval 'global $wpdb; $r=$wpdb->get_row("select score,max_score,time from {$wpdb->prefix}h5p_results where content_id='$HID'"); echo $r->score,"/",$r->max_score,"/",$r->time;' 2>/dev/null)" "1/1/52"
r=$(hsync '{"events":[{"id":"hs1","type":"h5p_statement","course_id":'$HC',"lesson_id":'$HL',"content_id":'$HID',"client_time":'$((now-8000))',"statement":'"$STJ"'}]}')
check "h5p: same answer sent twice is stored once" "$(echo "$r" | hst hs1)" "duplicate"
r=$(hsync '{"events":[{"id":"hq1","type":"quiz_attempt","course_id":'$HC',"lesson_id":'$HQ',"quiz_id":'$HQ',"client_time":'$((now-1000))',"started_at":'$((now-90000))',"question_ids":['$HQQ'],"answers":{"'$HQQ'":{"h5p":{"content_id":'$HID',"opened_at":'$((now-80000))',"statements":['"$STJ"']}}}}]}')
check "h5p quiz: attempt graded by Tutor (total, earned, result)" "$(echo "$r" | python3 -c "import json,sys;a=json.load(sys.stdin)['results'][0]['attempt'];print(float(a['total']),float(a['earned']),a['result'])")" "2.0 1.0 pass"
check "h5p quiz: H5P result linked to the attempt" "$($WPC eval 'global $wpdb; echo $wpdb->get_var("select count(*) from {$wpdb->prefix}tutor_h5p_quiz_result r join {$wpdb->prefix}tutor_quiz_attempts a on a.attempt_id=r.attempt_id where r.question_id='$HQQ' and r.raw_score=1");' 2>/dev/null)" "1"
$WPR eval '$o = get_option("tutor_option", []); $o["disable_complete_lesson_button"] = 0; update_option("tutor_option", $o);' >/dev/null
fi

# Speed optimizers (SiteGround) must leave Tutor's scripts alone
check "optimizer: Tutor scripts excluded from SiteGround minify/combine" "$($WPC eval 'do_action("wp_enqueue_scripts"); $h = apply_filters("sgo_js_minify_exclude", array("x")); $c = apply_filters("sgo_javascript_combine_exclude", array()); $t = array_filter($h, function($x){ return 0 === strpos($x, "tutor"); }); echo (in_array("x", $h) && count($t) > 0 && count(array_intersect($t, $c)) === count($t)) ? "yes" : "no";' 2>/dev/null)" "yes"

# Same device logging in again replaces its old token
OLD=$TOKEN
resp=$(curl -s -b $J -A "$UA" -X POST -d "device_id=dev-1&device_name=Pixel" "$B/?eduqode_app=issue_token")
TOKEN=$(echo "$resp" | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
check "re-login on same device: old token stops working" "$(curl -s -o /dev/null -w "%{http_code}" -H "X-EduQode-Token: $OLD" "$B/?rest_route=/eduqode/v1/me")" "401"
check "re-login on same device: new token works" "$(api -o /dev/null -w "%{http_code}" "$B/?rest_route=/eduqode/v1/me")" "200"

# Revoke
api -X POST -o /dev/null "$B/?rest_route=/eduqode/v1/auth/revoke"
code=$(api -o /dev/null -w "%{http_code}" "$B/?rest_route=/eduqode/v1/me")
check "revoked token no longer works" "$code" "401"

echo "----"; echo "passed: $pass  failed: $fail"
