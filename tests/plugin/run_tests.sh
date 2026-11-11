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
check "courses lists only enrolled course" "$(echo "$courses" | python3 -c "import json,sys;print([c['id'] for c in json.load(sys.stdin)['courses']])")" "[$C]"
check "course lesson_count" "$(echo "$courses" | python3 -c "import json,sys;print(json.load(sys.stdin)['courses'][0]['lesson_count'])")" "3"

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
check "sync: progress percent = Tutor's own (1 of 3 lessons + 1 quiz)" "$(echo "$r" | python3 -c "import json,sys;print(json.load(sys.stdin)['progress'][0]['percent'])")" "25"

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

# Website (Tutor LMS itself) sees the offline completion, and Tutor's redirect hook is restored
WPC="php ../wp/wp-cli.phar --allow-root --path=../wp/site"
check "website: Tutor marks lesson completed" "$($WPC eval 'echo tutor_utils()->is_completed_lesson('$L1', 2) ? "yes" : "no";' 2>/dev/null)" "yes"
check "website: Tutor best watched time updated (s)" "$($WPC eval 'echo (int) tutor_utils()->get_lesson_reading_info('$L2', 2, "video_best_watched_time");' 2>/dev/null)" "90"
check "website: enrollment intact" "$($WPC eval 'echo tutor_utils()->is_enrolled('$C', 2) ? "yes" : "no";' 2>/dev/null)" "yes"
check "website: Tutor redirect hook restored after sync" "$($WPC eval 'global $wp_filter; $n=0; foreach (($wp_filter["tutor_lesson_completed_after"]->callbacks ?? []) as $cbs) foreach ($cbs as $cb) if (is_array($cb["function"]) && $cb["function"][1]==="tutor_lesson_completed_after") $n++; echo $n;' 2>/dev/null)" "1"

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
