#!/bin/bash
# Builds a throw-away WordPress + Tutor LMS site (SQLite, no MySQL needed), installs the
# EduQode App Connector plugin, creates a test course and runs run_tests.sh against it.
# Usage: tests/plugin/setup_and_run.sh <work-dir>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${1:-/tmp/eduqode-plugin-test}"
mkdir -p "$WORK" && cd "$WORK"

[ -d site ] || git clone -q --depth 1 --branch 6.8.3 https://github.com/WordPress/WordPress.git site
[ -d sqlite ] || git clone -q --depth 1 https://github.com/WordPress/sqlite-database-integration.git sqlite
[ -d tutor ] || git clone -q --depth 1 https://github.com/themeum/tutor.git tutor
[ -f wp-cli.phar ] || curl -sSL -o wp-cli.phar https://github.com/wp-cli/wp-cli/releases/download/v2.12.0/wp-cli-2.12.0.phar
WP="php $WORK/wp-cli.phar --allow-root --path=$WORK/site"

P=site/wp-content/plugins/sqlite-database-integration
rm -rf "$P" && cp -rL sqlite/packages/plugin-sqlite-database-integration "$P"
sed -e "s#{SQLITE_IMPLEMENTATION_FOLDER_PATH}#$WORK/$P#; s#{SQLITE_PLUGIN}#sqlite-database-integration/load.php#" "$P/db.copy" > site/wp-content/db.php

T=site/wp-content/plugins/tutor
rm -rf "$T" && cp -r tutor "$T" && rm -rf "$T/.git" && mkdir -p "$T/vendor"
# Stand-in for "composer dump-autoload" (Tutor needs only its own PSR-4 classes).
cat > "$T/vendor/autoload.php" <<'PHP'
<?php
spl_autoload_register(function ($class) {
    $map = json_decode(file_get_contents(__DIR__ . '/../composer.json'), true)['autoload']['psr-4'];
    foreach ($map as $prefix => $dir) {
        if (strncmp($class, $prefix, strlen($prefix)) === 0) {
            $file = __DIR__ . '/../' . rtrim($dir, '/') . '/' . str_replace('\\', '/', substr($class, strlen($prefix))) . '.php';
            if (file_exists($file)) { require $file; return; }
        }
    }
});
PHP
# Test-only rules that imitate Tutor LMS Pro features (a completion rule, content not released yet).
mkdir -p site/wp-content/mu-plugins
cat > site/wp-content/mu-plugins/eduqode-test-rules.php <<'PHP'
<?php
add_filter('tutor_validate_lesson_complete', function ($ok, $user_id, $lesson_id) {
    return (int) get_option('eduqode_test_block_lesson') === (int) $lesson_id ? false : $ok;
}, 10, 3);
// Tutor LMS Pro's Assignments addon adds its post type to course contents the same way.
add_filter('tutor_course_contents_post_types', function ($types) { $types[] = 'tutor_assignments'; return $types; });
class EduQode_Test_ContentDrip {   // same hook and method name as Tutor LMS Pro 4.0.1 ContentDrip
    public function is_lock_lesson($id) { return (int) get_option('eduqode_test_drip_lesson') === (int) $id; }
    public function check_if_lesson_is_locked($s) { return $s; }
}
add_filter('tutor_lesson_show_mark_as_complete', [new EduQode_Test_ContentDrip(), 'check_if_lesson_is_locked']);
add_filter('eduqode_app_include_lesson', function ($ok, $lesson_id) {
    return (int) get_option('eduqode_test_hide_lesson') === (int) $lesson_id ? false : $ok;
}, 10, 2);
PHP
rm -rf site/wp-content/plugins/eduqode-app-connector
cp -r "$REPO/wordpress/eduqode-app-connector" site/wp-content/plugins/eduqode-app-connector

rm -rf site/wp-config.php site/wp-content/database
$WP config create --dbname=wp --dbuser=x --dbpass=x --skip-check --force >/dev/null
$WP core install --url=http://127.0.0.1:8099 --title=Test --admin_user=admin --admin_password=admin --admin_email=a@b.co --skip-email >/dev/null
$WP option update home http://127.0.0.1:8099 >/dev/null
$WP option update siteurl http://127.0.0.1:8099 >/dev/null
$WP plugin activate tutor >/dev/null
$WP plugin activate eduqode-app-connector >/dev/null
$WP plugin is-active eduqode-app-connector
echo "Tutor LMS $($WP eval 'echo TUTOR_VERSION;')  WordPress $($WP core version)"
$WP eval-file "$HERE/seed.php" | tail -1 > "$WORK/ids.json"

(cd site && php -S 127.0.0.1:8099 > "$WORK/server.log" 2>&1 &)
sleep 2
cp "$HERE/run_tests.sh" "$HERE/reset.php" "$WORK/"
cd "$WORK"
sed -i "s#WPC=\"php ../wp/wp-cli.phar --allow-root --path=../wp/site\"#WPC=\"$WP\"#" run_tests.sh
sed -i "s#WPR=\"php ../wp/wp-cli.phar --allow-root --path=../wp/site\"#WPR=\"$WP\"#" run_tests.sh
./run_tests.sh | tee results.txt
pkill -f "php -S 127.0.0.1:8099" || true
! grep -q '^FAIL' results.txt
