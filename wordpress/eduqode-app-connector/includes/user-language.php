<?php
/**
 * Each user's own language on the website (WordPress does this only inside wp-admin):
 *   - the language chosen in the user's profile (or with ?eduqode_lang=ur / ?eduqode_lang=en)
 *     is used on the pages of Tutor LMS, the School page and everything else for that user;
 *   - the app sets it with POST /eduqode/v1/me/language when the student picks Urdu or English.
 * WordPress's own Urdu files must be installed once (Settings → General → Site Language → اردو,
 * then set the site language back if the site should stay English for everyone else).
 * The same file is in EduQode App Connector and EduQode School; whichever loads first is used.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

// Declared only once even when both plugins are active (the first one loaded wins).
if ( ! function_exists( 'eduqode_user_language_locale' ) ) :

/** Languages the switcher offers: WordPress locale => name. */
function eduqode_user_languages() {
    return array( 'en_US' => 'English', 'ur' => 'اردو' );
}

/** The logged-in user's chosen language on the website, or '' for the site's language. */
function eduqode_user_language_locale() {
    if ( is_admin() || ! function_exists( 'wp_get_current_user' ) || ! did_action( 'plugins_loaded' ) ) {
        return '';
    }
    static $busy = false;
    if ( $busy ) {
        return '';
    }
    $busy   = true;
    $locale = is_user_logged_in() ? (string) get_user_meta( get_current_user_id(), 'locale', true ) : '';
    $busy   = false;
    return isset( eduqode_user_languages()[ $locale ] ) ? $locale : '';
}

add_filter( 'determine_locale', function ( $locale ) {
    $mine = eduqode_user_language_locale();
    return $mine ? $mine : $locale;
}, 20 );

/** Saves the user's language ('' = the site's language). */
function eduqode_set_user_language( $user_id, $locale ) {
    if ( '' !== $locale && ! isset( eduqode_user_languages()[ $locale ] ) ) {
        return false;
    }
    update_user_meta( $user_id, 'locale', $locale );
    return true;
}

// ?eduqode_lang=ur or ?eduqode_lang=en on any page switches the logged-in user's language.
add_action( 'template_redirect', function () {
    if ( empty( $_GET['eduqode_lang'] ) || ! is_user_logged_in() ) {
        return;
    }
    $want = 'ur' === $_GET['eduqode_lang'] ? 'ur' : 'en_US';
    eduqode_set_user_language( get_current_user_id(), $want );
    wp_safe_redirect( remove_query_arg( 'eduqode_lang' ) );
    exit;
}, 1 );

/** Small "English · اردو" switch (used on the School page). */
function eduqode_language_switch_html() {
    if ( ! is_user_logged_in() ) {
        return '';
    }
    $current = get_user_meta( get_current_user_id(), 'locale', true ) === 'ur' ? 'ur' : 'en';
    $links   = array();
    foreach ( array( 'en' => 'English', 'ur' => 'اردو' ) as $code => $name ) {
        $links[] = $code === $current ? '<strong>' . esc_html( $name ) . '</strong>'
            : '<a href="' . esc_url( add_query_arg( 'eduqode_lang', $code ) ) . '">' . esc_html( $name ) . '</a>';
    }
    return '<p class="eduqode-lang" style="text-align:end">' . implode( ' · ', $links ) . '</p>';
}

/**
 * Right to left for Urdu, also when WordPress's own Urdu language pack is not installed
 * (the pack is what normally tells WordPress that Urdu is written right to left).
 */
function eduqode_user_language_direction( $locale = '' ) {
    global $wp_locale;
    $locale = is_string( $locale ) && '' !== $locale ? $locale : determine_locale();
    if ( $wp_locale instanceof WP_Locale && 0 === strpos( $locale, 'ur' ) ) {
        $wp_locale->text_direction = 'rtl';
    }
}
add_action( 'init', 'eduqode_user_language_direction', 1 );
add_action( 'change_locale', 'eduqode_user_language_direction', 20 );

/** Urdu text needs a Nastaliq font and more line height. */
add_action( 'wp_enqueue_scripts', function () {
    if ( 0 === strpos( determine_locale(), 'ur' ) ) {
        wp_enqueue_style( 'eduqode-urdu-font', 'https://fonts.googleapis.com/css2?family=Noto+Nastaliq+Urdu:wght@400;600&display=swap', array(), null );
        wp_add_inline_style( 'eduqode-urdu-font', 'body,button,input,select,textarea,.tutor-wrap,.eqs{font-family:"Noto Nastaliq Urdu",serif}body{line-height:2}' );
    }
} );

endif;
