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
code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-EduQode-App: 1" -A "$UA" -X POST "$B/?eduqode_app=issue_token")
check "token: not logged in is refused" "$code" "401"
code=$(curl -s -o /dev/null -w "%{http_code}" -b $J -A "$UA" -X POST -d "device_id=dev-x" "$B/?eduqode_app=issue_token")
check "token: refused without the app's header (another website cannot ask for one)" "$code" "403"
resp=$(curl -s -b $J -A "$UA" -H "X-EduQode-App: 1" -X POST -d "device_id=dev-1&device_name=Pixel" "$B/?eduqode_app=issue_token")
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

# ---------------- Assignments written offline (Tutor LMS Pro Assignments addon) ----------------
ASG=$(id asg)
check "assignment: package says it can be submitted from the app" "$(api "$B/?rest_route=/eduqode/v1/courses/$C/package" | python3 -c "import json,sys;p=json.load(sys.stdin);a=[i for t in p['topics'] for i in t['items'] if i['id']==$ASG][0]['assignment'];print(a['offline_submit'],a['max_files'],a['retry'])")" "True 2 True"
printf 'x,y\n1,2\n' > answer.csv
sub() { api -s -o sub.json -w "%{http_code}" "$@" "$B/?rest_route=/eduqode/v1/assignments/$ASG/submit"; }
check "assignment: empty answer refused" "$(sub --form-string 'answer=<p></p>' -F client_id=empty-0001-aaaa)" "400"
check "assignment: too many files refused" "$(sub --form-string 'answer=<p>Done</p>' -F client_id=many-0001-aaaa -F 'attached_assignment_files[]=@answer.csv' -F 'attached_assignment_files[]=@answer.csv' -F 'attached_assignment_files[]=@answer.csv')" "400"
# A submission cut off half way (left "submitting") is finished by the retry, not reported as a duplicate.
$WPC eval 'global $wpdb; $wpdb->insert($wpdb->comments, ["comment_post_ID"=>'$ASG',"comment_approved"=>"submitting","comment_type"=>"tutor_assignment","user_id"=>'$(id student)',"comment_parent"=>'$C']); add_comment_meta($wpdb->insert_id, "_eduqode_client_id", "offline-0001-aaaa");' >/dev/null 2>&1
code=$(sub --form-string 'answer=<p>OFFLINE-ANSWER sales grew</p>' -F client_id=offline-0001-aaaa -F "started_at=$(( $(date +%s)*1000 - 3600000 ))" -F 'attached_assignment_files[]=@answer.csv;type=text/csv')
check "assignment: offline work submitted" "$code $(python3 -c "import json;r=json.load(open('sub.json'));print(r['status'],r['files'])")" "200 applied 1"
check "assignment: Tutor sees it submitted with the answer and file" "$($WPC eval 'global $wpdb; $c=$wpdb->get_row("select comment_ID,comment_approved,comment_content from {$wpdb->comments} where comment_type=\"tutor_assignment\" and comment_post_ID='$ASG' order by comment_ID desc"); $f=json_decode(get_comment_meta($c->comment_ID,"uploaded_attachments",true)); echo $c->comment_approved, " ", strpos($c->comment_content,"OFFLINE-ANSWER")!==false ? "answer" : "-", " ", (preg_match("/^answer(-\\d+)?\\.csv$/", $f[0]->name ?? "") ? "answer.csv" : ($f[0]->name ?? "-")), " ", tutor_utils()->is_assignment_submitted('$ASG','$(id student)') ? "yes":"no";' 2>/dev/null)" "submitted answer answer.csv yes"
code=$(sub --form-string 'answer=<p>OFFLINE-ANSWER sales grew</p>' -F client_id=offline-0001-aaaa)
check "assignment: same submission sent again is not doubled" "$code $(python3 -c "import json;print(json.load(open('sub.json'))['status'])") $($WPC eval 'global $wpdb; echo $wpdb->get_var("select count(*) from {$wpdb->comments} where comment_type=\"tutor_assignment\" and comment_post_ID='$ASG'");' 2>/dev/null)" "200 duplicate 1"
check "assignment: package shows it submitted" "$(api "$B/?rest_route=/eduqode/v1/courses/$C/package" | python3 -c "import json,sys;p=json.load(sys.stdin);print([i for t in p['topics'] for i in t['items'] if i['id']==$ASG][0]['assignment']['submitted'])")" "True"

# ---------------- Phone notifications ----------------
SINCE=$(( $(date +%s)*1000 - 5000 ))
check "notifications: nothing new yet" "$(api "$B/?rest_route=/eduqode/v1/notifications&since=$((SINCE+4000))" | python3 -c "import json,sys;print(len(json.load(sys.stdin)['notifications']))")" "0"
NOTE_IDS=$($WPC eval '
  $c='$C'; $t=(int) get_posts(["post_type"=>"topics","post_parent"=>$c,"fields"=>"ids","numberposts"=>1])[0];
  $l=wp_insert_post(["post_type"=>"lesson","post_status"=>"publish","post_title"=>"Brand new lesson","post_parent"=>$t,"menu_order"=>99]);
  $a=wp_insert_post(["post_type"=>"tutor_announcements","post_status"=>"publish","post_title"=>"Exam on Monday","post_content"=>"Bring a pencil.","post_parent"=>$c]);
  global $wpdb; $s=(int)$wpdb->get_var("select comment_ID from {$wpdb->comments} where comment_type=\"tutor_assignment\" and comment_post_ID='$ASG' order by comment_ID desc");
  update_comment_meta($s,"assignment_mark",8); update_comment_meta($s,"evaluate_time",gmdate("Y-m-d H:i:s"));
  $hc=(int) json_decode(file_get_contents("ids_h5p.json"),true)["h5p_course"]; $ht=(int) get_posts(["post_type"=>"topics","post_parent"=>$hc,"fields"=>"ids","numberposts"=>1])[0];
  $d=wp_insert_post(["post_type"=>"tutor_assignments","post_status"=>"publish","post_title"=>"Quick task","post_parent"=>$ht,"menu_order"=>9]);
  update_post_meta($d,"assignment_option",["total_mark"=>10,"pass_mark"=>5,"time_duration"=>["value"=>5,"time"=>"hours"]]);
  echo "$l $a $d";' 2>/dev/null)
api "$B/?rest_route=/eduqode/v1/notifications&since=$SINCE" > notes.json
check "notifications: new lesson, announcement, graded assignment, deadline" "$(python3 -c "
import json;n=json.load(open('notes.json'))['notifications'];t={x['type']:x for x in n}
new=sorted(x['title'] for x in n if x['type']=='new_content')
print(sorted(t), new, t['announcement']['title'], t['assignment_graded']['body'], t['deadline']['title'].startswith('Due soon: Quick task'))")" "['announcement', 'assignment_graded', 'deadline', 'new_content'] ['New assignment: Quick task', 'New lesson: Brand new lesson'] Exam on Monday You got 8 out of 10. True"
check "notifications: only enrolled courses" "$(python3 -c "import json;n=json.load(open('notes.json'))['notifications'];print(all(x['course_id'] in ($C,$(python3 -c "import json;print(json.load(open('ids_h5p.json'))['h5p_course'])")) for x in n))")" "True"
$WPC eval 'foreach (explode(" ", "'"$NOTE_IDS"'") as $id) wp_delete_post((int) $id, true);' >/dev/null 2>&1

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

# Urdu: the app sets the student's language; answers to the app come in Urdu.
check "language: app sets Urdu" "$(api -X POST -H "Content-Type: application/json" -d '{"language":"ur"}' "$B/?rest_route=/eduqode/v1/me/language" | python3 -c "import json,sys;print(json.load(sys.stdin)['locale'])")" "ur"
check "language: online-only reason in Urdu" "$(api "$B/?rest_route=/eduqode/v1/courses/$C/package" | python3 -c "import json,sys;p=json.load(sys.stdin);print([i for t in p['topics'] for i in t['items'] if i['id']==$QUIZ4][0]['online_reason'])")" "ایک H5P سوال آف لائن استعمال کے لیے تیار نہیں ہو سکا۔"
check "language: /me reports Urdu" "$(api "$B/?rest_route=/eduqode/v1/me" | python3 -c "import json,sys;print(json.load(sys.stdin)['locale'])")" "ur"
api -X POST -H "Content-Type: application/json" -d '{"language":"en"}' -o /dev/null "$B/?rest_route=/eduqode/v1/me/language"
check "language: app header X-EduQode-Lang ur wins" "$(api -H "X-EduQode-Lang: ur" "$B/?rest_route=/eduqode/v1/courses/$C/package" | python3 -c "import json,sys;p=json.load(sys.stdin);print([i for t in p['topics'] for i in t['items'] if i['id']==$QUIZ4][0]['online_reason'])")" "ایک H5P سوال آف لائن استعمال کے لیے تیار نہیں ہو سکا۔"
check "language: back to English" "$(api "$B/?rest_route=/eduqode/v1/courses/$C/package" | python3 -c "import json,sys;p=json.load(sys.stdin);print([i for t in p['topics'] for i in t['items'] if i['id']==$QUIZ4][0]['online_reason'])")" "An H5P question could not be prepared for offline use."

# Speed optimizers (SiteGround) must leave Tutor's scripts alone
check "optimizer: Tutor scripts excluded from SiteGround minify/combine" "$($WPC eval 'do_action("wp_enqueue_scripts"); $h = apply_filters("sgo_js_minify_exclude", array("x")); $c = apply_filters("sgo_javascript_combine_exclude", array()); $t = array_filter($h, function($x){ return 0 === strpos($x, "tutor"); }); echo (in_array("x", $h) && count($t) > 0 && count(array_intersect($t, $c)) === count($t)) ? "yes" : "no";' 2>/dev/null)" "yes"

# Same device logging in again replaces its old token
OLD=$TOKEN
resp=$(curl -s -b $J -A "$UA" -H "X-EduQode-App: 1" -X POST -d "device_id=dev-1&device_name=Pixel" "$B/?eduqode_app=issue_token")
TOKEN=$(echo "$resp" | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
check "re-login on same device: old token stops working" "$(curl -s -o /dev/null -w "%{http_code}" -H "X-EduQode-Token: $OLD" "$B/?rest_route=/eduqode/v1/me")" "401"
check "re-login on same device: new token works" "$(api -o /dev/null -w "%{http_code}" "$B/?rest_route=/eduqode/v1/me")" "200"

# Login safety
SID=$($WPC user get student --field=ID)
check "token: unused for 180 days stops working" "$($WPC eval '$u='$SID'; $t=get_user_meta($u,"_eduqode_app_tokens",true); $k=array_key_first($t); $saved=$t; $t[$k]["last_used"]=time()-181*DAY_IN_SECONDS; update_user_meta($u,"_eduqode_app_tokens",$t); $r=EduQode_App_Auth::user_from_token("'$TOKEN'"); update_user_meta($u,"_eduqode_app_tokens",$saved); echo $r;')" "0"
check "google hand-off: off unless the admin turns it on" "$(curl -s -o /dev/null -w "%{http_code}" "$B/?eduqode_app_handoff=1")" "200"
$WPC option update eduqode_app_google_login handoff >/dev/null
check "google hand-off: refused without the app's challenge" "$(curl -s -o /dev/null -w "%{http_code}" "$B/?eduqode_app_handoff=1")" "400"
VER=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcdef
CH=$(python3 -c "import hashlib,base64,sys;print(base64.urlsafe_b64encode(hashlib.sha256(sys.argv[1].encode()).digest()).decode().rstrip('='))" "$VER")
link=$(curl -s -b $J "$B/?eduqode_app_handoff=1&challenge=$CH" | grep -o 'eduqode://auth?token=[A-Za-z0-9]*' | head -1)
check "google hand-off: logged-in user is sent back to the app" "$(echo "$link" | grep -c token=)" "1"
OT=${link#*token=}
check "google hand-off: token without the app's secret is useless" "$(curl -s -o /dev/null -w "%{redirect_url}" "$B/?eduqode_app_token=$OT&eduqode_app_verifier=wrong" | grep -c dashboard)" "0"
link=$(curl -s -b $J "$B/?eduqode_app_handoff=1&challenge=$CH" | grep -o 'eduqode://auth?token=[A-Za-z0-9]*' | head -1); OT=${link#*token=}
check "google hand-off: works with the app's secret" "$(curl -s -o /dev/null -w "%{redirect_url}" "$B/?eduqode_app_token=$OT&eduqode_app_verifier=$VER" | grep -c dashboard)" "1"
check "google hand-off: token works only once" "$(curl -s -o /dev/null -w "%{redirect_url}" "$B/?eduqode_app_token=$OT&eduqode_app_verifier=$VER" | grep -c dashboard)" "0"
$WPC option update eduqode_app_google_login off >/dev/null
$WPC user update student --user_pass=student >/dev/null
check "new password: app tokens on all phones stop working" "$(api -o /dev/null -w "%{http_code}" "$B/?rest_route=/eduqode/v1/me")" "401"
curl -s -c $J -b "wordpress_test_cookie=WP%20Cookie%20check" -A "$UA" -o /dev/null -d "log=student&pwd=student&wp-submit=Log+In&testcookie=1" $B/wp-login.php
TOKEN=$(curl -s -b $J -A "$UA" -H "X-EduQode-App: 1" -X POST -d "device_id=dev-1" "$B/?eduqode_app=issue_token" | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")

# H5P for instructors (Tutor LMS dashboard → Interactive exercises)
INST=$($WPC eval '$u=wp_insert_user(["user_login"=>"inst1","user_pass"=>"inst1pass","user_email"=>"inst1@example.com","role"=>"subscriber"]); update_user_meta($u,"_is_tutor_instructor",time()); update_user_meta($u,"_tutor_instructor_status","approved"); (new WP_User($u))->add_role(tutor()->instructor_role); echo $u;')
check "h5p studio: instructor can make own H5P, nothing more" "$($WPC eval '$u='$INST'; foreach(["edit_h5p_contents","edit_others_h5p_contents","manage_h5p_libraries","install_recommended_h5p_libraries","disable_h5p_security"] as $c) echo (int)user_can($u,$c);')" "10000"
check "h5p studio: students cannot" "$($WPC eval 'echo (int)user_can(get_user_by("login","student")->ID,"edit_h5p_contents");')" "0"
IJ=$PWD/inst.jar
curl -s -c $IJ -b "wordpress_test_cookie=WP%20Cookie%20check" -o /dev/null -d "log=inst1&pwd=inst1pass&wp-submit=Log+In&testcookie=1" $B/wp-login.php
DASH=$($WPC eval 'echo get_permalink((int)tutor_utils()->get_option("tutor_dashboard_page_id"));')
page=$(curl -s -b $IJ "$DASH?tutor_dashboard_page=h5p-exercises&new=1")
check "h5p studio: editor page in the dashboard" "$(echo "$page" | grep -c 'page=h5p_new&amp;eduqode_frame=1\|page=h5p_new&#038;eduqode_frame=1')" "1"
frame=$(curl -s -b $IJ "$B/wp-admin/admin.php?page=h5p_new&eduqode_frame=1")
check "h5p studio: H5P editor opens for the instructor, without wp-admin menus" "$(echo "$frame" | grep -c 'id="h5p-content-form"\|id="eduqode-h5p-frame"')" "2"
NONCE=$(echo "$frame" | grep -o 'name="yes_sir_will_do" value="[^"]*"' | sed 's/.*value="//;s/"//')
LIB=$($WPC eval 'global $wpdb; $l=$wpdb->get_row("SELECT name,major_version,minor_version FROM {$wpdb->prefix}h5p_libraries WHERE name=\"H5P.TrueFalse\""); echo "$l->name $l->major_version.$l->minor_version";')
PARAMS='{"params":{"question":"<p>Is cotton a natural fibre?</p>","correct":"true","behaviour":{"enableRetry":true,"enableSolutionsButton":true,"enableCheckButton":true,"confirmCheckDialog":false,"confirmRetryDialog":false,"autoCheck":false}},"metadata":{"title":"Instructor exercise","license":"U"}}'
loc=$(curl -s -o /dev/null -w "%{redirect_url}" -b $IJ --data-urlencode "yes_sir_will_do=$NONCE" --data-urlencode "action=create" --data-urlencode "title=Instructor exercise" --data-urlencode "library=$LIB" --data-urlencode "parameters=$PARAMS" "$B/wp-admin/admin.php?page=h5p_new&eduqode_frame=1")
NEWID=$(echo "$loc" | grep -o 'eduqode_h5p_saved=[0-9]*' | cut -d= -f2)
check "h5p studio: instructor creates an exercise with the H5P editor" "$($WPC eval 'global $wpdb; echo $wpdb->get_var("SELECT user_id FROM {$wpdb->prefix}h5p_contents WHERE id=".(int)"'$NEWID'");')" "$INST"
check "h5p studio: after saving, the frame tells the dashboard (no wp-admin page)" "$(curl -s -b $IJ "$loc" | grep -c 'eduqodeH5P:\"saved\",id:'$NEWID)" "1"
list=$(curl -s -b $IJ "$DASH?tutor_dashboard_page=h5p-exercises")
check "h5p studio: list shows own exercise with its lesson code" "$(echo "$list" | grep -c "\[h5p id=&quot;$NEWID&quot;\]\|\[h5p id=\"$NEWID\"\]")" "1"
check "h5p studio: list does not show other people's exercises" "$(echo "$list" | grep -c 'Is the sky blue?')" "0"
other=$(curl -s -b $IJ "$B/wp-admin/admin.php?page=h5p_new&eduqode_frame=1&id=$HID")
check "h5p studio: cannot edit someone else's exercise" "$(echo "$other" | grep -c 'not allowed to edit this content')" "1"
# Course builder (Tutor LMS Pro) H5P lists
tnonce() { curl -s -b "$1" "$DASH" | grep -o '"_tutor_nonce":"[^"]*"' | head -1 | cut -d'"' -f4; }
AJ=$PWD/admin.jar
curl -s -c $AJ -b "wordpress_test_cookie=WP%20Cookie%20check" -o /dev/null -d "log=admin&pwd=admin&wp-submit=Log+In&testcookie=1" $B/wp-login.php
AN=$(tnonce $AJ); IN=$(tnonce $IJ)
blist() { curl -s -b "$1" --data "action=$2&_tutor_nonce=$3&search_filter=$4" "$B/wp-admin/admin-ajax.php"; }
check "course builder: admin's lesson list has the instructor's new exercise" "$(blist $AJ tutor_h5p_list_lesson_contents "$AN" "" | python3 -c "import json,sys;d=json.load(sys.stdin)['data']['output'];print([r['title'] for r in d if str(r['id'])=='$NEWID'])")" "['Instructor exercise — inst1']"
check "course builder: admin's quiz list has it too" "$(blist $AJ tutor_h5p_list_quiz_contents "$AN" "instructor" | python3 -c "import json,sys;print(len(json.load(sys.stdin)['data']['output']))")" "1"
check "course builder: instructor's list has only own exercises" "$(blist $IJ tutor_h5p_list_lesson_contents "$IN" "" | python3 -c "import json,sys;print(sorted({str(r['user_id']) for r in json.load(sys.stdin)['data']['output']}))")" "['$INST']"
check "course builder: wrong nonce refused" "$(blist $IJ tutor_h5p_list_lesson_contents bad "" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['success'], 'output' in (d.get('data') or {}))")" "False False"
DN=$(echo "$list" | grep -o 'name="_wpnonce" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//')
code=$(curl -s -o /dev/null -w "%{http_code}" -b $IJ --data "eduqode_h5p_delete=$HID&_wpnonce=$DN" "$DASH?tutor_dashboard_page=h5p-exercises")
check "h5p studio: cannot delete someone else's exercise" "$code" "403"
curl -s -o /dev/null -b $IJ --data "eduqode_h5p_delete=$NEWID&_wpnonce=$DN" "$DASH?tutor_dashboard_page=h5p-exercises"
check "h5p studio: deletes own exercise" "$($WPC eval 'global $wpdb; echo (int)$wpdb->get_var("SELECT COUNT(*) FROM {$wpdb->prefix}h5p_contents WHERE id=".(int)"'$NEWID'");')" "0"
$WPC option update eduqode_app_instructor_h5p 0 >/dev/null
check "h5p studio: admin can switch it off" "$($WPC eval 'echo (int)user_can('$INST',"edit_others_h5p_contents"), (int)EduQode_App_H5P_Studio::enabled();')" "00"
$WPC option update eduqode_app_instructor_h5p 1 >/dev/null

# Homepage = Tutor login page: a logged-in user goes to the dashboard
LP=$($WPC eval '$id=wp_insert_post(["post_type"=>"page","post_status"=>"publish","post_title"=>"Login","post_name"=>"tutor-login","post_content"=>"[tutor_login]"]); update_option("show_on_front","page"); update_option("page_on_front",$id); echo $id;')
check "login page: logged-in user goes to the dashboard" "$(curl -s -o /dev/null -w "%{redirect_url}" -b $J "$B/")" "$($WPC eval 'echo tutor_utils()->tutor_dashboard_url();')"
check "login page: visitors still see the login page" "$(curl -s -o /dev/null -w "%{http_code}" "$B/")" "200"
$WPC eval 'update_option("show_on_front","posts"); delete_option("page_on_front"); wp_delete_post('$LP', true);' >/dev/null

# Revoke
api -X POST -o /dev/null "$B/?rest_route=/eduqode/v1/auth/revoke"
code=$(api -o /dev/null -w "%{http_code}" "$B/?rest_route=/eduqode/v1/me")
check "revoked token no longer works" "$code" "401"

echo "----"; echo "passed: $pass  failed: $fail"
