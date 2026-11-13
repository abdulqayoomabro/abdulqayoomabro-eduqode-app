<?php
/**
 * Plugin Name: EduQode App Connector
 * Description: Connects the EduQode Android app to Tutor LMS: offline course packs, offline progress sync, offline video links, and Google login hand-off. Does not change Tutor LMS or WordPress core files.
 * Version: 2.11.0
 * Requires at least: 5.8
 * Requires PHP: 7.4
 * Author: EduQode
 * Text Domain: eduqode-app-connector
 * Domain Path: /languages
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

define( 'EDUQODE_APP_VERSION', '2.11.0' );
define( 'EDUQODE_APP_DIR', plugin_dir_path( __FILE__ ) );

/** True when the request comes from the EduQode Android app's web view. */
function eduqode_is_app() {
    $ua = isset( $_SERVER['HTTP_USER_AGENT'] ) ? $_SERVER['HTTP_USER_AGENT'] : '';
    return strpos( $ua, 'EduQodeApp' ) !== false;
}

/** True when Tutor LMS is active and its helper object is available. */
function eduqode_app_tutor_ready() {
    return function_exists( 'tutor_utils' ) && function_exists( 'tutor' );
}

/** Translation of the plugin's own text (languages/eduqode-app-connector-ur.mo for Urdu). */
function eqa__( $text ) {
    return translate( $text, 'eduqode-app-connector' );
}
add_action( 'init', function () {
    load_plugin_textdomain( 'eduqode-app-connector', false, dirname( plugin_basename( __FILE__ ) ) . '/languages' );
} );

/**
 * Answers to the app in a user's language. switch_to_user_locale() does nothing when WordPress's own
 * language pack is not installed, so the plugin's Urdu file is loaded directly in that case.
 * Returns true when the language was changed (call eqa_restore_language() afterwards).
 */
function eqa_use_user_language( $user_id, $locale = null ) {
    $locale = null === $locale ? (string) get_user_meta( $user_id, 'locale', true ) : $locale;
    if ( '' === $locale ) {
        return false;
    }
    if ( function_exists( 'switch_to_locale' ) && switch_to_locale( $locale ) ) {
        $GLOBALS['eqa_language_stack'][] = 'switched';
        return true;
    }
    $mo = EDUQODE_APP_DIR . 'languages/eduqode-app-connector-' . $locale . '.mo';
    if ( ! is_readable( $mo ) && 'en_US' !== $locale ) {
        return false;
    }
    unload_textdomain( 'eduqode-app-connector' );
    if ( 'en_US' !== $locale ) {
        load_textdomain( 'eduqode-app-connector', $mo, $locale );
    }
    $GLOBALS['eqa_language_stack'][] = 'loaded';
    return true;
}

function eqa_restore_language() {
    $how = array_pop( $GLOBALS['eqa_language_stack'] );
    if ( 'switched' === $how ) {
        restore_previous_locale();
    } elseif ( 'loaded' === $how ) {
        unload_textdomain( 'eduqode-app-connector' );
        load_plugin_textdomain( 'eduqode-app-connector', false, dirname( plugin_basename( __FILE__ ) ) . '/languages' );
    }
}

require_once EDUQODE_APP_DIR . 'includes/user-language.php';
require_once EDUQODE_APP_DIR . 'includes/tutor-urdu.php';
require_once EDUQODE_APP_DIR . 'includes/login-redirect.php';
require_once EDUQODE_APP_DIR . 'includes/google-login.php';
require_once EDUQODE_APP_DIR . 'includes/optimizer-compat.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-auth.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-sync.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-h5p.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-h5p-studio.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-no-earnings.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-quiz.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-assignments.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-notifications.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-courses.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-config.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-rest.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-admin.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-backup.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-backup-admin.php';

EduQode_App_Auth::init();
EduQode_App_Rest::init();
EduQode_App_Admin::init();
EduQode_App_Backup_Admin::init();
EduQode_App_H5P_Studio::init();
EduQode_App_No_Earnings::init();
