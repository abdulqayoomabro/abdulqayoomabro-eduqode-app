<?php
/**
 * Urdu for Tutor LMS's student and teacher screens (languages/tutor/tutor-ur.mo, made by
 * tools/i18n/tutor). An official Urdu pack of Tutor LMS, when installed, is loaded first and wins;
 * this file fills in the texts it does not have.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

function eqa_load_tutor_urdu( $locale = '' ) {
    $locale = is_string( $locale ) && '' !== $locale ? $locale : determine_locale();
    $ours   = EDUQODE_APP_DIR . 'languages/tutor/tutor-ur.mo';
    if ( 0 !== strpos( $locale, 'ur' ) || ! is_readable( $ours ) || ! apply_filters( 'eduqode_tutor_urdu', true ) ) {
        return;
    }
    unload_textdomain( 'tutor', true );
    $official = WP_LANG_DIR . '/plugins/tutor-' . $locale . '.mo';
    if ( is_readable( $official ) || is_readable( substr( $official, 0, -3 ) . '.l10n.php' ) ) {
        load_textdomain( 'tutor', $official, $locale );
    }
    load_textdomain( 'tutor', $ours, $locale );
}
add_action( 'init', 'eqa_load_tutor_urdu', 5 );
// Also when the language changes during a request (e-mails or answers in a student's language).
add_action( 'change_locale', function ( $locale ) {
    if ( did_action( 'init' ) ) {
        unload_textdomain( 'tutor', true );
        eqa_load_tutor_urdu( $locale );
    }
} );

// "English · اردو" at the top of the Tutor LMS dashboard (turn off with the eduqode_dashboard_language_switch filter).
add_action( 'tutor_dashboard/before/wrap', function () {
    if ( function_exists( 'eduqode_language_switch_html' ) && apply_filters( 'eduqode_dashboard_language_switch', true ) ) {
        echo eduqode_language_switch_html(); // phpcs:ignore -- escaped inside
    }
} );
