#!/bin/bash
# EduQode School on a real WordPress + Tutor LMS (the plugin test site, which already has quiz
# attempts and a graded assignment for "student"). Every check logs in as a real user over HTTP.
# Usage: tests/school/run_school_tests.sh <work-dir of tests/plugin/setup_and_run.sh>
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
WORK="$1"; cd "$WORK"
WP="php $WORK/wp-cli.phar --allow-root --path=$WORK/site"
B=http://127.0.0.1:8099
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (expected: $3, got: $2)"; fail=$((fail+1)); fi; }
has() { if grep -q -- "$2" "$3"; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (missing: $2)"; fail=$((fail+1)); fi; }
hasnt() { if grep -q -- "$2" "$3"; then echo "FAIL  $1  (found: $2)"; fail=$((fail+1)); else echo "PASS  $1"; pass=$((pass+1)); fi; }
login() { rm -f "jar_$1"; curl -s -c "jar_$1" -b "wordpress_test_cookie=WP%20Cookie%20check" -o /dev/null -d "log=$1&pwd=$2&wp-submit=Log+In&testcookie=1" "$B/wp-login.php"; }
get() { curl -s -b "jar_$1" "$2"; }

SERVER=""
if ! curl -s -o /dev/null "$B/"; then
  (cd site && exec nohup php -S 127.0.0.1:8099 > "$WORK/school-server.log" 2>&1 < /dev/null) &
  SERVER=$!; sleep 2
fi
trap '[ -n "$SERVER" ] && kill $SERVER 2>/dev/null' EXIT
rm -rf site/wp-content/plugins/eduqode-school && cp -r "$REPO/wordpress/eduqode-school" site/wp-content/plugins/
$WP plugin activate eduqode-school >/dev/null
check "activate: tables, Parent role and School page" "$($WP eval 'global $wpdb; echo count($wpdb->get_col("SHOW TABLES LIKE \"%eqs_%\"")), " ", get_role("eqs_parent") ? "role" : "-", " ", get_post_status((int) get_option("eqs_page_id"));')" "7 role publish"
PAGE=$($WP eval 'echo get_permalink((int) get_option("eqs_page_id"));')
url() { python3 -c "import sys,urllib.parse as u;b=sys.argv[1];q=sys.argv[2];print(b+('&' if '?' in b else '?')+q)" "$PAGE" "$1"; }

C=$(python3 -c "import json;print(json.load(open('ids.json'))['course'])")
S1=$(python3 -c "import json;print(json.load(open('ids.json'))['student'])")
IDS=$($WP eval '
  $t  = wp_insert_user(["user_login"=>"teacher1","user_pass"=>"teacher1","user_email"=>"t1@b.co","role"=>"editor","display_name"=>"Ms Teacher"]);
  $t2 = wp_insert_user(["user_login"=>"teacher2","user_pass"=>"teacher2","user_email"=>"t2@b.co","role"=>"editor","display_name"=>"Mr Other"]);
  $s2 = wp_insert_user(["user_login"=>"student2","user_pass"=>"student2","user_email"=>"s2@b.co","role"=>"subscriber","display_name"=>"Sara Student"]);
  $p1 = wp_insert_user(["user_login"=>"parent1","user_pass"=>"parent1","user_email"=>"p1@b.co","role"=>"subscriber","display_name"=>"Ali Father"]);
  $p2 = wp_insert_user(["user_login"=>"parent2","user_pass"=>"parent2","user_email"=>"p2@b.co","role"=>"subscriber","display_name"=>"Sara Mother"]);
  $cls = EQS_Model::save_group(["kind"=>"class","name"=>"Class 7","session"=>"2026-27","class_teacher_id"=>$t]);
  $sec = EQS_Model::save_group(["kind"=>"section","name"=>"A","parent_id"=>$cls]);
  $bat = EQS_Model::save_group(["kind"=>"batch","name"=>"Evening batch","class_teacher_id"=>$t2]);
  EQS_Model::add_subject($cls, '$C', $t, "Computer Science");
  EQS_Model::add_student($sec, '$S1', "1"); EQS_Model::add_student($sec, $s2, "2");
  EQS_Model::link_parent($p1, '$S1', "Father"); EQS_Model::link_parent($p2, $s2, "Mother");
  $term = EQS_Reports::add_term("Term 1", gmdate("Y-m-d", strtotime("-30 days")), gmdate("Y-m-d", strtotime("+30 days")), "2026-27");
  echo json_encode(compact("t","t2","s2","p1","p2","cls","sec","bat","term"));' 2>&1 | tail -1)
echo "$IDS" > school_ids.json
i() { python3 -c "import json;print(json.load(open('school_ids.json'))['$1'])"; }
SEC=$(i sec); CLS=$(i cls); BAT=$(i bat); TERM=$(i term); S2=$(i s2)
check "setup: student2 enrolled in the subject's course by joining the section" "$($WP eval 'echo tutor_utils()->is_enrolled('$C', '$S2') ? "yes" : "no";')" "yes"
check "setup: parent got the Parent role" "$($WP eval 'echo in_array("eqs_parent", (new WP_User('$(i p1)'))->roles, true) ? "yes" : "no";')" "yes"

# Results match Tutor LMS's own tables (best finished attempt per quiz; graded assignment 8/10).
EXPECTED=$($WP eval 'global $wpdb; $q = EQS_Stats::quiz_ids('$C'); $best = [];
  foreach ($wpdb->get_results("select quiz_id, earned_marks, total_marks from {$wpdb->prefix}tutor_quiz_attempts where user_id='$S1' and attempt_status in (\"attempt_ended\",\"review_required\")") as $a)
    if (in_array((int)$a->quiz_id, $q, true) && $a->total_marks > 0) $best[$a->quiz_id] = max($best[$a->quiz_id] ?? 0, $a->earned_marks * 100 / $a->total_marks);
  echo round(array_sum($best) / count($best), 1);')
GOT=$($WP eval '$r = EQS_Stats::subject('$C', '$S1'); echo $r["quiz_avg"], " ", $r["asg_avg"], " ", $r["overall"], " ", $r["grade"];')
check "results: quiz average = Tutor's attempts, assignment 80% (8/10), overall 50/50, grade" "$GOT" "$EXPECTED 80 $(python3 -c "q=$EXPECTED;o=round((q*50+80*50)/100,1);print(int(o) if o==int(o) else o, end=' ');print('A+' if o>=90 else 'A' if o>=80 else 'B' if o>=70 else 'C' if o>=60 else 'D' if o>=50 else 'F')")"

# ---- teacher ----
login teacher1 teacher1
get teacher1 "$PAGE" > t_home.html
has "teacher: home lists the section" "Class 7 – A" t_home.html
hasnt "teacher: does not see other teacher's batch" "Evening batch" t_home.html
get teacher1 "$(url "view=group&id=$SEC")" > t_group.html
has "teacher: class results list the students" "Sara Student" t_group.html
has "teacher: class average row" "Class average" t_group.html
get teacher1 "$(url "view=group&id=$BAT")" > t_bat.html
has "teacher: other batch refused" "You do not have access" t_bat.html
TODAY=$($WP eval 'echo current_time("Y-m-d");')
get teacher1 "$(url "view=attendance&group=$SEC")" > t_att.html
NONCE=$(grep -o 'name="_wpnonce" value="[^"]*"' t_att.html | head -1 | sed 's/.*value="//;s/"//')
code=$(curl -s -o /dev/null -w "%{http_code}" -b jar_teacher1 --data "eqs_action=attendance&group=$SEC&date=$TODAY&_wpnonce=$NONCE&status[$S1]=P&status[$S2]=A&status[999999]=P" "$(url "view=attendance&group=$SEC")")
check "attendance: saved (redirect back)" "$code" "302"
check "attendance: rows for the two students only" "$($WP eval 'print_r(EQS_Attendance::day('$SEC', "'$TODAY'"));' | tr -d ' \n')" "Array([$S1]=>P[$S2]=>A)"
curl -s -o /dev/null -b jar_teacher1 --data "eqs_action=attendance&group=$SEC&date=2099-01-01&_wpnonce=$NONCE&status[$S1]=A" "$(url "view=attendance&group=$SEC")"
check "attendance: a future day is not saved" "$($WP eval 'echo count(EQS_Attendance::day('$SEC', "2099-01-01"));')" "0"
login teacher2 teacher2
code=$(curl -s -o /dev/null -w "%{http_code}" -b jar_teacher2 --data "eqs_action=attendance&group=$SEC&date=$TODAY&_wpnonce=$NONCE&status[$S1]=A" "$(url "view=attendance&group=$SEC")")
check "attendance: another teacher cannot change it" "$code $($WP eval 'echo EQS_Attendance::day('$SEC', "'$TODAY'")['$S1'];')" "403 P"
get teacher1 "$(url "view=remarks&group=$SEC&term=$TERM")" > t_rem.html
RN=$(grep -o 'name="_wpnonce" value="[^"]*"' t_rem.html | head -1 | sed 's/.*value="//;s/"//')
curl -s -o /dev/null -b jar_teacher1 --data-urlencode "remark[$S1]=Works hard. REMARK-MARKER" --data "eqs_action=remarks&group=$SEC&term=$TERM&_wpnonce=$RN" "$(url "view=remarks&group=$SEC&term=$TERM")"
check "report card: teacher's remark saved" "$($WP eval 'echo EQS_Reports::remark('$TERM', '$SEC', '$S1');')" "Works hard. REMARK-MARKER"

# ---- parents ----
loc=$(curl -s -o /dev/null -w "%{redirect_url}" -b "wordpress_test_cookie=WP%20Cookie%20check" -d "log=parent1&pwd=parent1&wp-submit=Log+In&testcookie=1" "$B/wp-login.php")
check "parent: lands on the School page after login" "$loc" "$PAGE"
login parent1 parent1
get parent1 "$PAGE" > p_home.html
has "parent: sees own child" "Ali Student" p_home.html
hasnt "parent: does not see other children" "Sara Student" p_home.html
get parent1 "$(url "view=student&id=$S1")" > p_child.html
has "parent: child's subject results" "Computer Science" p_child.html
has "parent: child's attendance" "Attendance <strong>100%" p_child.html
get parent1 "$(url "view=student&id=$S2")" > p_other.html
has "parent: another child refused" "You do not have access" p_other.html
get parent1 "$(url "view=group&id=$SEC")" > p_group.html
has "parent: class results refused" "You do not have access" p_group.html
get parent1 "$(url "view=report&student=$S1&group=$SEC&term=$TERM")" > p_report.html
has "report card: subject, teacher's remark, attendance" "REMARK-MARKER" p_report.html
has "report card: print button" "window.print()" p_report.html
has "report card: attendance in the term" "1 of 1 days present" p_report.html
login parent2 parent2
get parent2 "$(url "view=report&student=$S1&group=$SEC&term=$TERM")" > p2_report.html
has "report card: another parent refused" "You do not have access" p2_report.html

# ---- student ----
login student2 student2
get student2 "$(url "view=student&id=$S2")" > s_self.html
has "student: own page with absence" "Absent 1" s_self.html
get student2 "$(url "view=student&id=$S1")" > s_other.html
has "student: classmate refused" "You do not have access" s_other.html

# ---- admin pages render ----
check "admin pages render without PHP notices" "$($WP eval 'wp_set_current_user(1); ob_start(); $_GET=[]; EQS_Admin::page_groups(); $_GET["group"]='$SEC'; EQS_Admin::page_groups(); EQS_Admin::page_parents(); EQS_Admin::page_terms(); $h=ob_get_clean(); echo (strpos($h,"Computer Science")!==false && strpos($h,"Ali Father")!==false && strpos($h,"Term 1")!==false) ? "ok" : "missing";' 2>&1 | tail -1)" "ok"

[ -f "$WORK/school-server.log" ] && check "no PHP warnings or errors while serving the pages" "$(grep -cE 'PHP (Warning|Notice|Fatal|Deprecated)' "$WORK/school-server.log")" "0"
echo "----"; echo "passed: $pass  failed: $fail"
[ "$fail" = 0 ]
