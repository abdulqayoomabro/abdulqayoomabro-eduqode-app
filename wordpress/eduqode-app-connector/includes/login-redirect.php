<?php
/**
 * A logged-in user who opens the login page (for example the homepage, when the homepage is
 * Tutor LMS's login page) goes to the Tutor LMS dashboard instead of seeing the login form again.
 * Turn off with: add_filter( 'eduqode_login_page_redirect', '__return_false' );
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

function eduqode_is_login_page( $post ) {
    if ( ! $post instanceof WP_Post ) {
        return false;
    }
    $tutor_login = function_exists( 'tutor_utils' ) ? (int) tutor_utils()->get_option( 'tutor_login_page' ) : 0;
    return ( $tutor_login && (int) $post->ID === $tutor_login )
        || has_shortcode( (string) $post->post_content, 'tutor_login' )
        || 0 === strpos( (string) $post->post_name, 'tutor-login' );
}

add_action( 'template_redirect', function () {
    if ( ! is_user_logged_in() || ! function_exists( 'tutor_utils' ) || ! apply_filters( 'eduqode_login_page_redirect', true ) ) {
        return;
    }
    if ( ! ( is_singular() || is_front_page() ) || ! empty( $_GET['eduqode_app_handoff'] ) || ! empty( $_GET['redirect_to'] ) ) {
        return;
    }
    $post = is_front_page() && 'page' === get_option( 'show_on_front' ) ? get_post( (int) get_option( 'page_on_front' ) ) : get_queried_object();
    if ( ! eduqode_is_login_page( $post ) ) {
        return;
    }
    $dashboard = tutor_utils()->tutor_dashboard_url();
    if ( $dashboard && untrailingslashit( $dashboard ) !== untrailingslashit( get_permalink( $post ) ) ) {
        wp_safe_redirect( $dashboard );
        exit;
    }
}, 5 );
