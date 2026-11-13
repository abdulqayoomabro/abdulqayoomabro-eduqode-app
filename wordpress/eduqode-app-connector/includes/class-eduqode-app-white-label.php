<?php
/**
 * White label: the site shows its own brand (EduQode App → General → "Brand name") instead of
 * "WordPress" and "Tutor LMS" (Pro):
 *   - "Tutor LMS Pro" / "Tutor LMS" / "Tutor Pro" in Tutor LMS's texts (menus, dashboard, e-mails),
 *   - login page: site logo, brand name, no "Powered by WordPress", no "— WordPress" in the title,
 *   - wp-admin: no WordPress logo, footer or news, no "— WordPress" in the title,
 *   - page source: no generator tags (WordPress, Elementor, …), no WordPress version on files,
 *     no wp-json / shortlink / RSD links, e-mails not "from WordPress",
 *   - the public user list of the REST API (it shows user names) is closed to visitors.
 * Plugin and file names (wp-content/…) stay as they are: renaming them breaks updates.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_White_Label {

    const OPTION_ON    = 'eduqode_app_white_label';
    const OPTION_BRAND = 'eduqode_app_brand';
    const DEFAULT      = 'Customised LMS by Abro';

    public static function init() {
        if ( ! self::enabled() ) {
            return;
        }
        // Texts of Tutor LMS (front end, dashboard, wp-admin menus, e-mails).
        add_filter( 'gettext', array( __CLASS__, 'text' ), 20, 3 );
        add_filter( 'gettext_with_context', array( __CLASS__, 'text_ctx' ), 20, 4 );
        add_filter( 'ngettext', array( __CLASS__, 'text_n' ), 20, 5 );

        // Page source.
        remove_action( 'wp_head', 'wp_generator' );
        add_filter( 'the_generator', '__return_empty_string' );
        remove_action( 'wp_head', 'rsd_link' );
        remove_action( 'wp_head', 'wlwmanifest_link' );
        remove_action( 'wp_head', 'wp_shortlink_wp_head' );
        remove_action( 'wp_head', 'rest_output_link_wp_head' );
        remove_action( 'wp_head', 'wp_oembed_add_discovery_links' );
        remove_action( 'template_redirect', 'rest_output_link_header', 11 );
        remove_action( 'template_redirect', 'wp_shortlink_header', 11 );
        add_action( 'wp_head', array( __CLASS__, 'head_start' ), -10000 );
        add_action( 'wp_head', array( __CLASS__, 'head_end' ), PHP_INT_MAX );
        add_filter( 'style_loader_src', array( __CLASS__, 'no_wp_version' ), 20 );
        add_filter( 'script_loader_src', array( __CLASS__, 'no_wp_version' ), 20 );
        add_filter( 'rest_endpoints', array( __CLASS__, 'rest_endpoints' ) );
        add_filter( 'wp_mail_from_name', array( __CLASS__, 'mail_from_name' ) );

        // Login page.
        add_filter( 'login_headerurl', function () {
            return home_url( '/' );
        } );
        add_filter( 'login_headertext', array( __CLASS__, 'brand' ) );
        add_filter( 'login_title', array( __CLASS__, 'title' ) );
        add_action( 'login_enqueue_scripts', array( __CLASS__, 'login_style' ) );

        // wp-admin and the admin bar.
        add_filter( 'admin_title', array( __CLASS__, 'title' ) );
        add_filter( 'admin_footer_text', function () {
            return esc_html( self::brand() );
        }, 99 );
        add_filter( 'update_footer', '__return_empty_string', 99 );
        add_action( 'admin_bar_menu', function ( $bar ) {
            $bar->remove_node( 'wp-logo' );
        }, 999 );
        add_action( 'wp_dashboard_setup', function () {
            remove_meta_box( 'dashboard_primary', 'dashboard', 'side' );
        }, 99 );
    }

    public static function enabled() {
        return '1' === get_option( self::OPTION_ON, '1' );
    }

    public static function brand() {
        $b = trim( (string) get_option( self::OPTION_BRAND, self::DEFAULT ) );
        return '' !== $b ? $b : self::DEFAULT;
    }

    // ------------------------------------------------------------ texts

    private static function rebrand( $s ) {
        if ( false === stripos( $s, 'tutor' ) ) {
            return $s;
        }
        $b = self::brand();
        return str_replace( array( 'Tutor LMS Pro', 'Tutor LMS PRO', 'Tutor LMS', 'Tutor Pro', 'TutorLMS' ), $b, $s );
    }

    private static function is_tutor_domain( $domain ) {
        return 'tutor' === $domain || 'tutor-pro' === $domain || 0 === strpos( (string) $domain, 'tutor-' );
    }

    public static function text( $translation, $text, $domain ) {
        return self::is_tutor_domain( $domain ) ? self::rebrand( $translation ) : $translation;
    }

    public static function text_ctx( $translation, $text, $context, $domain ) {
        return self::is_tutor_domain( $domain ) ? self::rebrand( $translation ) : $translation;
    }

    public static function text_n( $translation, $single, $plural, $number, $domain ) {
        return self::is_tutor_domain( $domain ) ? self::rebrand( $translation ) : $translation;
    }

    /** "Log In ‹ Site — WordPress" → "Log In ‹ Site". */
    public static function title( $title ) {
        return trim( preg_replace( '/\s*(&#8212;|&mdash;|—|-)\s*WordPress\s*$/u', '', (string) $title ) );
    }

    // ------------------------------------------------------------ page source

    public static function head_start() {
        ob_start();
    }

    /** Removes every <meta name="generator"> (WordPress, Elementor, WooCommerce, …). */
    public static function head_end() {
        $html = ob_get_clean();
        echo preg_replace( '#<meta\s+name=["\']generator["\'][^>]*>\s*#i', '', (string) $html ); // phpcs:ignore
    }

    public static function no_wp_version( $src ) {
        global $wp_version;
        return ( $src && false !== strpos( $src, 'ver=' . $wp_version ) ) ? remove_query_arg( 'ver', $src ) : $src;
    }

    /** The REST API's list of users shows user names; only logged-in users can use it. */
    public static function rest_endpoints( $endpoints ) {
        if ( ! is_user_logged_in() ) {
            foreach ( array_keys( $endpoints ) as $route ) {
                if ( 0 === strpos( $route, '/wp/v2/users' ) ) {
                    unset( $endpoints[ $route ] );
                }
            }
        }
        return $endpoints;
    }

    public static function mail_from_name( $name ) {
        return 'WordPress' === $name ? self::brand() : $name;
    }

    // ------------------------------------------------------------ login page

    public static function login_style() {
        $logo = '';
        $id   = (int) get_theme_mod( 'custom_logo' );
        if ( $id ) {
            $logo = (string) wp_get_attachment_image_url( $id, 'medium' );
        }
        if ( ! $logo ) {
            $logo = (string) get_site_icon_url( 192 );
        }
        if ( $logo ) {
            echo '<style id="eduqode-login-brand">#login h1 a{background-image:url(' . esc_url( $logo ) . ')!important;background-size:contain!important;width:100%!important;max-width:240px;height:90px!important}</style>';
        } else {
            // No logo set: the brand name as text instead of the WordPress logo.
            echo '<style id="eduqode-login-brand">#login h1 a{background:none!important;text-indent:0!important;width:auto!important;height:auto!important;font-size:22px;font-weight:600;color:#1d2327;line-height:1.3}</style>';
        }
    }
}
