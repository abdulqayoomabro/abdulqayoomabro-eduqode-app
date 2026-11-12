<?php
/**
 * Plugin Name: EduQode School
 * Description: Classes, sections and batches on top of Tutor LMS: teacher dashboard (subject-wise and class-wise results), parent dashboard, attendance and term report cards. Works on the website and inside the EduQode app.
 * Version: 1.0.0
 * Requires PHP: 7.4
 * Author: EduQode
 * License: GPLv2 or later
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

define( 'EQS_VERSION', '1.0.0' );
define( 'EQS_DIR', plugin_dir_path( __FILE__ ) );
define( 'EQS_URL', plugin_dir_url( __FILE__ ) );

require_once EQS_DIR . 'includes/class-eqs-db.php';
require_once EQS_DIR . 'includes/class-eqs-model.php';
require_once EQS_DIR . 'includes/class-eqs-stats.php';
require_once EQS_DIR . 'includes/class-eqs-attendance.php';
require_once EQS_DIR . 'includes/class-eqs-reports.php';
require_once EQS_DIR . 'includes/class-eqs-front.php';
require_once EQS_DIR . 'includes/class-eqs-admin.php';

register_activation_hook( __FILE__, array( 'EQS_DB', 'activate' ) );
add_action( 'plugins_loaded', array( 'EQS_DB', 'maybe_upgrade' ) );
EQS_Front::init();
EQS_Admin::init();

/** Tutor LMS must be active for results and progress. */
function eqs_tutor_ready() {
    return function_exists( 'tutor_utils' ) && function_exists( 'tutor' );
}
