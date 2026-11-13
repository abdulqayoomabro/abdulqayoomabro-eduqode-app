<?php
/**
 * Company training: no money in the Tutor LMS dashboard (EduQode App → General → "Company
 * training: hide earnings", on by default). For everyone, admins too (Tutor LMS's reports in
 * wp-admin stay):
 *   - instructor home: no "Total Earnings" card, no "Earnings Over Time" chart, top courses by
 *     students without the revenue column or the Revenue/Students switch,
 *   - no Withdrawals page, no Withdraw tab in settings, no "Set Withdraw Method" in profile
 *     completion, no students' Billing page,
 *   - no Analytics menu of Tutor LMS Pro (its pages are full of earnings; learners' progress is in
 *     the School page's learning insights),
 *   - those pages' addresses lead back to the dashboard.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_No_Earnings {

    const OPTION = 'eduqode_app_hide_earnings';

    /** Tutor LMS's own stat card template, used by our wrapper for every card except earnings. */
    public static $stat_card = '';

    public static function init() {
        add_filter( 'tutor_dashboard/instructor_nav_items', array( __CLASS__, 'nav' ), 99 );
        add_filter( 'tutor_dashboard_account_pages', array( __CLASS__, 'account_pages' ), 99 );
        add_filter( 'tutor_dashboard/nav_items/settings/nav_items', array( __CLASS__, 'settings_tabs' ), 99 );
        add_filter( 'tutor/user/profile/completion', array( __CLASS__, 'completion' ), 99 );
        add_filter( 'tutor_dashboard_section_render', array( __CLASS__, 'section' ), 10, 3 );
        add_filter( 'tutor_get_template_path', array( __CLASS__, 'template' ), 99, 2 );
        add_action( 'template_redirect', array( __CLASS__, 'block_pages' ), 3 );
        add_action( 'wp_head', array( __CLASS__, 'style' ) );
        add_action( 'template_redirect', array( __CLASS__, 'sort_by_students' ), 4 );
    }

    public static function enabled() {
        return '1' === get_option( self::OPTION, '1' );
    }

    /** Applies to every visitor of the front-end dashboard (admins keep Tutor LMS's reports in wp-admin). */
    public static function applies() {
        return self::enabled();
    }

    public static function nav( $items ) {
        if ( self::applies() ) {
            unset( $items['analytics'], $items['withdraw'], $items['withdrawals'], $items['earning'], $items['earnings'] );
        }
        return $items;
    }

    public static function account_pages( $pages ) {
        if ( self::applies() ) {
            unset( $pages['withdrawals'], $pages['billing'] );
        }
        return $pages;
    }

    public static function settings_tabs( $tabs ) {
        if ( self::applies() ) {
            unset( $tabs['withdraw'], $tabs['billing-address'] );
        }
        return $tabs;
    }

    public static function completion( $fields ) {
        if ( self::applies() ) {
            unset( $fields['_tutor_withdraw_method_data'] );
        }
        return $fields;
    }

    /** Instructor home sections (loaded by AJAX). */
    public static function section( $result, $section_id, $params ) {
        if ( ! self::applies() || ! class_exists( '\TUTOR\DashboardSectionManager' ) || ! class_exists( '\TUTOR\InstructorMetricsAdapter' ) ) {
            return $result;
        }
        $uid   = (int) ( $params['user_id'] ?? get_current_user_id() );
        $start = (string) ( $params['start_date'] ?? '' );
        $end   = (string) ( $params['end_date'] ?? '' );
        switch ( $section_id ) {
            case 'overview_chart':
                return array( 'html' => '', 'has_data' => false );
            case 'current_stats':
                require_once EDUQODE_APP_DIR . 'includes/tutor-section-renderer.php';
                $cards = array_values( array_filter(
                    \TUTOR\InstructorMetricsAdapter::get_stat_cards( $uid, $start, $end ),
                    function ( $card ) {
                        return ( $card['icon'] ?? '' ) !== ( defined( '\TUTOR\Icon::EARNING' ) ? \TUTOR\Icon::EARNING : 'earning' );
                    }
                ) );
                return EduQode_Tutor_Sections::stats( $cards, $params );
            case 'top_performing_courses':
                require_once EDUQODE_APP_DIR . 'includes/tutor-section-renderer.php';
                $params['type']                  = 'student';
                $params['top_performing_course'] = 'student';
                return EduQode_Tutor_Sections::top( \TUTOR\InstructorMetricsAdapter::get_top_performing_courses( $uid, 'student', $start, $end ), $params );
        }
        return $result;
    }

    /** Top courses without revenue (our copies of two small Tutor LMS templates). */
    public static function template( $path, $template ) {
        if ( ! self::applies() ) {
            return $path;
        }
        $t = str_replace( '\\', '/', (string) $template );
        // Works the same in Tutor LMS 4.0 (home built in one template) and 4.1+ (sections by AJAX).
        if ( 'dashboard/instructor/analytics/stat-card' === $t ) {
            self::$stat_card = $path;
            return EDUQODE_APP_DIR . 'templates/tutor/stat-card.php';
        }
        if ( 'dashboard/instructor/home/overview-chart' === $t ) {
            return EDUQODE_APP_DIR . 'templates/tutor/empty.php';
        }
        if ( 'dashboard/instructor/home/top-performing-course-item' === $t ) {
            return EDUQODE_APP_DIR . 'templates/tutor/top-performing-course-item.php';
        }
        if ( 'dashboard/instructor/home/top-performing-course-filter' === $t ) {
            return EDUQODE_APP_DIR . 'templates/tutor/empty.php';
        }
        return $path;
    }

    /** Old links (analytics, withdrawals, billing, withdraw settings) go to the dashboard. */
    public static function block_pages() {
        if ( ! self::applies() || ! is_user_logged_in() || ! function_exists( 'tutor_utils' ) ) {
            return;
        }
        global $wp_query;
        $page = (string) ( $wp_query->query_vars['tutor_dashboard_page'] ?? '' );
        $sub  = (string) ( $wp_query->query_vars['tutor_dashboard_sub_page'] ?? '' );
        $tab  = isset( $_GET['tab'] ) ? sanitize_key( wp_unslash( $_GET['tab'] ) ) : ''; // phpcs:ignore
        $blocked = in_array( $page, array( 'analytics', 'withdraw', 'withdrawals', 'earning', 'earnings', 'purchase_history' ), true )
            || ( 'account' === $page && preg_match( '#^(withdrawals|billing)(/|$)#', $sub ) )
            || ( 'account' === $page && 0 === strpos( $sub, 'settings' ) && in_array( $tab, array( 'withdraw', 'billing-address' ), true ) );
        if ( $blocked ) {
            wp_safe_redirect( tutor_utils()->tutor_dashboard_url() );
            exit;
        }
    }

    /** Is this stat card about money? */
    public static function is_money_card( $title, $icon ) {
        $earning_icon = defined( '\TUTOR\Icon::EARNING' ) ? \TUTOR\Icon::EARNING : 'earning';
        $titles       = array( 'Total Earnings', 'Total Earning', 'Earnings', 'Revenue', 'Total Revenue' );
        foreach ( $titles as $t ) {
            if ( $title === $t || $title === __( $t, 'tutor' ) || $title === __( $t, 'tutor-pro' ) ) { // phpcs:ignore
                return true;
            }
        }
        return $icon === $earning_icon;
    }

    public static function style() {
        if ( self::applies() && function_exists( 'tutor_utils' ) && tutor_utils()->is_tutor_frontend_dashboard() ) {
            // The earnings chart's section and its switch; the empty box left where the earnings card was.
            echo '<style id="eduqode-no-earnings">.tutor-popover-menu-item[data-id="overview_chart"],[data-section-id="overview_chart"]{display:none!important}' .
                '[data-section-id="current_stats"] .tutor-flex-1:not(:has(*)){display:none!important}</style>';
        }
    }

    /** Tutor LMS 4.0 sorts "Top Performing Courses" by revenue unless told otherwise. */
    public static function sort_by_students() {
        if ( self::applies() && ! isset( $_GET['type'] ) && function_exists( 'tutor_utils' ) && tutor_utils()->is_tutor_frontend_dashboard() ) { // phpcs:ignore
            $_GET['type'] = 'student'; // phpcs:ignore
        }
    }
}
