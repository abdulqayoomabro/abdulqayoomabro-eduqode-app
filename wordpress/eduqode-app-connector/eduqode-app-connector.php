<?php
/**
 * Plugin Name: EduQode App Connector
 * Description: Connects the EduQode Android app to Tutor LMS: offline course packs, offline progress sync, offline video links, and Google login hand-off. Does not change Tutor LMS or WordPress core files.
 * Version: 2.1.0
 * Requires at least: 5.8
 * Requires PHP: 7.4
 * Author: EduQode
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

define( 'EDUQODE_APP_VERSION', '2.1.0' );
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

require_once EDUQODE_APP_DIR . 'includes/google-login.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-auth.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-sync.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-quiz.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-courses.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-rest.php';
require_once EDUQODE_APP_DIR . 'includes/class-eduqode-app-admin.php';

EduQode_App_Auth::init();
EduQode_App_Rest::init();
EduQode_App_Admin::init();
