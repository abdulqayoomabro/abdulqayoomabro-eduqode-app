<?php
/**
 * Settings for the Android app, served by this site (before: files in the GitHub repo).
 * GET /wp-json/eduqode/v1/app-config  (public: the app reads it before anyone logs in)
 * Edited in Settings > EduQode App > App settings.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Config {

    const DEFAULT_CSS = '/* Hide prices, buy / enroll buttons and the cart icon inside the app */
.in-app .tutor-course-sidebar-card-pricing,
.in-app .tutor-course-price,
.in-app .tutor-course-loop-price,
.in-app .tutor-add-to-cart-button,
.in-app .tutor-native-add-to-cart,
.in-app .tutor-woocommerce-add-to-cart,
.in-app .single_add_to_cart_button,
.in-app .add_to_cart_button,
.in-app .woocommerce-Price-amount,
.in-app .price,
.in-app .elementor-menu-cart__wrapper,
.in-app .elementor-widget-woocommerce-menu-cart,
.in-app a[href*="add-to-cart"],
.in-app a[href*="/cart"],
.in-app a[href*="/checkout"] {
  display: none !important;
}';

    const DEFAULT_BLOCKED_PATHS = "/cart\n/checkout\n/basket\n/my-cart";
    const DEFAULT_BLOCKED_QUERY = "add-to-cart\ntutor_pay_now";

    private static function lines( $text ) {
        return array_values( array_filter( array_map( 'trim', preg_split( '/[\r\n]+/', (string) $text ) ) ) );
    }

    public static function data() {
        return array(
            'config' => array(
                'googleLogin'        => get_option( 'eduqode_app_google_login', 'off' ),
                'googleLoginMessage' => 'Google login is not available in the app yet. Please log in with your email and password. If you signed up with Google, tap \'Forgot password\' once to set a password.',
                'blockedPathParts'   => self::lines( get_option( 'eduqode_app_blocked_paths', self::DEFAULT_BLOCKED_PATHS ) ),
                'blockedQueryParts'  => self::lines( get_option( 'eduqode_app_blocked_query', self::DEFAULT_BLOCKED_QUERY ) ),
                // EduQode School plugin: the app shows a "School" tab that opens this page.
                'schoolUrl'          => ( class_exists( 'EQS_Model' ) && 'publish' === get_post_status( (int) get_option( 'eqs_page_id' ) ) )
                    ? (string) get_permalink( (int) get_option( 'eqs_page_id' ) ) : '',
            ),
            'css'    => (string) get_option( 'eduqode_app_css', self::DEFAULT_CSS ),
        );
    }

    public static function save( $post ) {
        $mode = isset( $post['google_login'] ) && 'handoff' === $post['google_login'] ? 'handoff' : 'off';
        update_option( 'eduqode_app_google_login', $mode );
        update_option( 'eduqode_app_blocked_paths', sanitize_textarea_field( wp_unslash( $post['blocked_paths'] ?? '' ) ) );
        update_option( 'eduqode_app_blocked_query', sanitize_textarea_field( wp_unslash( $post['blocked_query'] ?? '' ) ) );
        // CSS only: tags are removed so nothing but styling can reach the app.
        update_option( 'eduqode_app_css', wp_strip_all_tags( wp_unslash( $post['app_css'] ?? '' ) ) );
    }
}
