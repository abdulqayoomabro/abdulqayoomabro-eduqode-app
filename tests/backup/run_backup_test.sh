#!/bin/bash
# Backup & restore between two real WordPress + Tutor LMS + H5P sites.
# Site A = the plugin test site (after tests/plugin/setup_and_run.sh, which leaves students' H5P
# results in it). Site B = a fresh copy of the same software with an empty database.
# Usage: tests/backup/run_backup_test.sh <work-dir of tests/plugin/setup_and_run.sh>
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$1"
A="php $WORK/wp-cli.phar --allow-root --path=$WORK/site"
B="php $WORK/wp-cli.phar --allow-root --path=$WORK/siteB"
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (expected: $3, got: $2)"; fail=$((fail+1)); fi; }

# ---- site A: backup ----
HC=$(python3 -c "import json;print(json.load(open('$WORK/ids_h5p.json'))['h5p_course'])")
$A option update eduqode_app_css "body{color:red}" >/dev/null
$A post meta update "$(python3 -c "import json;print(json.load(open('$WORK/ids_h5p.json'))['h5p_lesson'])")" _eduqode_offline_video_url https://cdn.example.com/h5p.mp4 >/dev/null
$A eduqode backup export --courses="$HC" --students --file="$WORK/backup.zip" || { echo "FAIL export"; exit 1; }
$A eval-file "$HERE/dump_course.php" "$HC" | tail -1 > "$WORK/tutor_export.json"
check "backup zip has manifest and the H5P exercise" "$(python3 -c "
import zipfile,json;z=zipfile.ZipFile('$WORK/backup.zip');m=json.loads(z.read('manifest.json'))
print(len([n for n in z.namelist() if n.startswith('h5p/') and n.endswith('.h5p')]), len(m['courses']), bool(m['students']['quiz_results']), bool(m['students']['lesson_statements']))")" "1 1 True True"
check "backup has no passwords or app tokens" "$(unzip -p "$WORK/backup.zip" manifest.json | grep -c -E 'user_pass|_eduqode_app_tokens|_eduqode_app_events')" "0"

# ---- site B: same software, empty database ----
rm -rf "$WORK/siteB"; mkdir -p "$WORK/siteB"
(cd "$WORK/site" && tar --exclude=./wp-content/database --exclude=./wp-content/uploads --exclude=./wp-config.php -cf - .) | (cd "$WORK/siteB" && tar -xf -)
sed "s#$WORK/site/#$WORK/siteB/#g" "$WORK/site/wp-content/db.php" > "$WORK/siteB/wp-content/db.php"
P=$WORK/siteB/wp-content/plugins/sqlite-database-integration
$B config create --dbname=wp --dbuser=x --dbpass=x --skip-check --force >/dev/null
$B core install --url=http://127.0.0.1:8098 --title=SiteB --admin_user=admin --admin_password=admin --admin_email=a@b.co --skip-email >/dev/null
for p in tutor h5p eduqode-app-connector; do $B plugin activate $p >/dev/null; done
# Different user ids than site A (an extra user first), same student email.
$B user create someone x@b.co --role=subscriber --user_pass=x >/dev/null
SB=$($B user create student2 s@b.co --role=subscriber --user_pass=x --porcelain)
# Another exercise already on site B gets the id the backup's exercise had on site A.
OTHER=$($B eval-file "$HERE/../plugin/h5p_import.php" "$HERE/../plugin/fixtures/true-false.h5p" "Site B own exercise" | tail -1)
$B eval-file "$HERE/simulate_tutor_import.php" "$WORK/tutor_export.json" | tail -1 > "$WORK/import_map.json"
NC=$(python3 -c "import json;m=json.load(open('$WORK/import_map.json'));print(m['posts']['$HC'])")
OLDL=$(python3 -c "import json;print(json.load(open('$WORK/ids_h5p.json'))['h5p_lesson'])")
OLDQQ=$(python3 -c "import json;print(json.load(open('$WORK/ids_h5p.json'))['h5p_question'])")
NL=$(python3 -c "import json;m=json.load(open('$WORK/import_map.json'));print(m['posts']['$OLDL'])")
NQQ=$(python3 -c "import json;m=json.load(open('$WORK/import_map.json'));print(m['questions']['$OLDQQ'])")
check "site B: course has new ids (like Tutor's import)" "$([ "$NC" != "$HC" ] && [ "$NL" != "$OLDL" ] && echo yes)" "yes"

# ---- site B: restore ----
R=$($B eduqode backup import "$WORK/backup.zip" --format=json | tail -1); echo "report: $R"
r() { echo "$R" | python3 -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }
check "restore: course found" "$(r courses)" "1"
check "restore: H5P exercise imported" "$(r h5p_imported)" "1"
NEWH=$($B eval 'global $wpdb; echo $wpdb->get_var("select max(id) from {$wpdb->prefix}h5p_contents");')
check "restore: exercise got a new id (site B's own exercise kept)" "$([ "$NEWH" != "$OTHER" ] && echo yes)" "yes"
check "restore: lesson now uses the restored exercise" "$($B post get "$NL" --field=post_content | grep -o '\[h5p id="[0-9]*"\]')" "[h5p id=\"$NEWH\"]"
check "restore: H5P quiz question uses the restored exercise" "$($B eval 'global $wpdb; echo $wpdb->get_var("select question_description from {$wpdb->prefix}tutor_quiz_questions where question_id='$NQQ'");')" "$NEWH"
check "restore: exercise is the same (library + answer)" "$($B eval '$c=H5P_Plugin::get_instance()->get_h5p_instance("core")->loadContent('$NEWH'); echo $c["library"]["name"], " ", json_decode($c["params"])->correct;')" "H5P.TrueFalse false"
check "restore: offline video link and app setting" "$($B post meta get "$NL" _eduqode_offline_video_url) $($B option get eduqode_app_css)" "https://cdn.example.com/h5p.mp4 body{color:red}"
check "restore: student's H5P finished mark on the new lesson" "$($B user meta get "$SB" "_tutor_h5p_lesson_completed_$NL")" "1"
check "restore: student's H5P plugin result" "$($B eval 'global $wpdb; $r=$wpdb->get_row("select score,max_score from {$wpdb->prefix}h5p_results where user_id='$SB' and content_id='$NEWH'"); echo $r ? $r->score."/".$r->max_score : "none";')" "1/1"
check "restore: Tutor Pro lesson statement moved to new lesson + student" "$($B eval 'global $wpdb; echo $wpdb->get_var("select count(*) from {$wpdb->prefix}tutor_h5p_lesson_statement where user_id='$SB' and lesson_id='$NL' and content_id='$NEWH'");')" "1"
check "restore: H5P quiz result linked to the imported attempt" "$($B eval 'global $wpdb; echo $wpdb->get_var("select count(*) from {$wpdb->prefix}tutor_h5p_quiz_result r join {$wpdb->prefix}tutor_quiz_attempts a on a.attempt_id=r.attempt_id and a.user_id=r.user_id where r.user_id='$SB' and r.question_id='$NQQ' and r.raw_score=1");')" "1"
check "restore: app plays the restored exercise (package)" "$($B eval '$l=[]; $c=EduQode_App_H5P::content('$NEWH',$l); echo $c ? $c["library"] : "none", " ", count($l);')" "H5P.TrueFalse 8"

# ---- run again: nothing doubled ----
R=$($B eduqode backup import "$WORK/backup.zip" --format=json | tail -1)
check "restore again: exercise not imported twice" "$(r h5p_imported) $(r h5p_reused)" "0 1"
check "restore again: students' results not added twice" "$(r students_skipped) $($B eval 'global $wpdb; echo $wpdb->get_var("select count(*) from {$wpdb->prefix}tutor_h5p_quiz_result where user_id='$SB'");')" "True 1"
check "restore again: H5P contents on site B" "$($B eval 'global $wpdb; echo $wpdb->get_var("select count(*) from {$wpdb->prefix}h5p_contents");')" "2"

echo "----"; echo "passed: $pass  failed: $fail"
[ "$fail" = 0 ]
