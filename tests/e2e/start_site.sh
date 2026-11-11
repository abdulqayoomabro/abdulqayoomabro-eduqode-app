#!/bin/bash
# Prepares the WordPress + Tutor LMS test site (built by tests/plugin/setup_and_run.sh) for the
# emulator: site address 10.0.2.2 (how the emulator reaches this computer), a real MP4, and the
# E2E course. Then keeps the server running in the background.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$1"
WP="php $WORK/wp-cli.phar --allow-root --path=$WORK/site"
mkdir -p "$WORK/site/wp-content/uploads/e2e"
if [ ! -f "$WORK/site/wp-content/uploads/e2e/big.mp4" ]; then
  # 30-second playable video at a fixed 3 Mbit/s (about 11 MB), so a download takes several seconds.
  "${FFMPEG:-ffmpeg}" -loglevel error -y -f lavfi -i testsrc=duration=30:size=640x360:rate=25 \
    -c:v libx264 -x264-params nal-hrd=cbr -b:v 3M -minrate 3M -maxrate 3M -bufsize 1M \
    -pix_fmt yuv420p -movflags +faststart -f mp4 "$WORK/site/wp-content/uploads/e2e/big.mp4"
fi
ls -la "$WORK/site/wp-content/uploads/e2e/big.mp4"
cp "$HERE/slow.php" "$WORK/site/slow.php"
# Test-only action: "the teacher changes the course" (publishes a lesson, adds a question to the empty quiz).
mkdir -p "$WORK/site/wp-content/mu-plugins"
cat > "$WORK/site/wp-content/mu-plugins/e2e-actions.php" <<'PHP'
<?php
add_action('init', function () {
    if (($_GET['e2e_action'] ?? '') !== 'update_course') return;
    global $wpdb;
    $ids = json_decode(file_get_contents(ABSPATH . 'e2e-ids.json'), true);
    wp_update_post(['ID' => $ids['l4'], 'post_status' => 'publish']);
    $wpdb->insert($wpdb->prefix . 'tutor_quiz_questions', ['quiz_id' => $ids['q'], 'question_title' => 'Added later', 'question_description' => '',
        'question_type' => 'true_false', 'question_mark' => 1, 'question_settings' => maybe_serialize([]), 'question_order' => 1]);
    $qid = (int) $wpdb->insert_id;
    $wpdb->insert($wpdb->prefix . 'tutor_quiz_question_answers', ['belongs_question_id' => $qid, 'belongs_question_type' => 'true_false', 'answer_title' => 'True', 'is_correct' => 1, 'answer_order' => 1]);
    $wpdb->insert($wpdb->prefix . 'tutor_quiz_question_answers', ['belongs_question_id' => $qid, 'belongs_question_type' => 'true_false', 'answer_title' => 'False', 'is_correct' => 0, 'answer_order' => 2]);
    wp_send_json(['ok' => true]);
});
PHP
$WP option update home http://10.0.2.2:8099 >/dev/null
$WP option update siteurl http://10.0.2.2:8099 >/dev/null
$WP eval-file "$HERE/seed_e2e.php"
pkill -f "php -S 0.0.0.0:8099" || true
cd "$WORK/site"
PHP_CLI_SERVER_WORKERS=8 nohup php -S 0.0.0.0:8099 > "$WORK/e2e-server.log" 2>&1 &
sleep 2
curl -s -o /dev/null -w "test site: %{http_code}\n" http://127.0.0.1:8099/e2e-ids.json
