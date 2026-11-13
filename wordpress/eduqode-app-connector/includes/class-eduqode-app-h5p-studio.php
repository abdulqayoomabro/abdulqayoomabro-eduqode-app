<?php
/**
 * H5P for instructors, from the Tutor LMS dashboard (Dashboard → Interactive exercises).
 *
 * The H5P plugin lets only people who can open wp-admin create content, and Tutor LMS (Pro) can
 * close wp-admin for instructors. Here an approved Tutor LMS instructor can:
 *   - see the H5P exercises they made, make new ones, edit and delete them,
 *   - use the real H5P editor (the H5P plugin's own page, shown inside the dashboard without
 *     the wp-admin menus), so everything the H5P editor does keeps working,
 *   - only with content types the admin installed; they cannot install or change content types,
 *     and cannot edit other people's exercises.
 * Admin: EduQode App → "Instructors can make H5P exercises" (on by default).
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_H5P_Studio {

    const OPTION = 'eduqode_app_instructor_h5p';
    const SLUG   = 'h5p-exercises';
    const CAPS   = array( 'edit_h5p_contents', 'view_h5p_contents', 'view_h5p_results' );

    public static function init() {
        add_filter( 'user_has_cap', array( __CLASS__, 'caps' ), 10, 4 );
        add_filter( 'tutor_dashboard/instructor_nav_items', array( __CLASS__, 'nav' ), 30 );
        add_filter( 'load_dashboard_template_part_from_other_location', array( __CLASS__, 'template' ), 30 );
        add_action( 'admin_init', array( __CLASS__, 'frame_access' ), 1 );
        add_action( 'admin_head', array( __CLASS__, 'frame_style' ) );
        add_filter( 'wp_redirect', array( __CLASS__, 'frame_redirect' ), 20 );
        add_action( 'template_redirect', array( __CLASS__, 'delete' ), 2 );
        add_action( 'template_redirect', array( __CLASS__, 'saved_page' ), 1 );
        // Course builder (Tutor LMS Pro): its H5P lists show only the current user's own exercises.
        add_action( 'wp_ajax_tutor_h5p_list_lesson_contents', array( __CLASS__, 'builder_list_lesson' ), 1 );
        add_action( 'wp_ajax_tutor_h5p_list_quiz_contents', array( __CLASS__, 'builder_list_quiz' ), 1 );
        // New dashboard page: Tutor LMS's pretty URLs need the rewrite rules once.
        add_action( 'init', function () {
            if ( EDUQODE_APP_VERSION !== get_option( 'eduqode_app_h5p_rules' ) ) {
                update_option( 'eduqode_app_h5p_rules', EDUQODE_APP_VERSION );
                add_action( 'wp_loaded', 'flush_rewrite_rules' );
            }
        }, 99 );
    }

    public static function enabled() {
        return '1' === get_option( self::OPTION, '1' ) && class_exists( 'H5P_Plugin' ) && function_exists( 'tutor_utils' );
    }

    /** An approved Tutor LMS instructor (not only someone who applied). */
    public static function is_instructor( $user_id ) {
        return $user_id && function_exists( 'tutor_utils' ) && tutor_utils()->is_instructor( $user_id, true );
    }

    /** Instructors get "make and edit own H5P content" — never library or other people's content rights. */
    public static function caps( $allcaps, $caps, $args, $user ) {
        if ( ! array_intersect( $caps, self::CAPS ) || ! self::enabled() || ! self::is_instructor( $user->ID ) ) {
            return $allcaps;
        }
        foreach ( self::CAPS as $c ) {
            $allcaps[ $c ] = true;
        }
        return $allcaps;
    }

    // ------------------------------------------------------------ dashboard page

    public static function url( $args = array() ) {
        $base = get_permalink( (int) tutor_utils()->get_option( 'tutor_dashboard_page_id' ) );
        return add_query_arg( array_merge( array( 'tutor_dashboard_page' => self::SLUG ), $args ), $base );
    }

    public static function nav( $items ) {
        if ( self::enabled() ) {
            $items[ self::SLUG ] = array(
                'title'    => eqa__( 'Interactive exercises (H5P)' ),
                'icon'     => class_exists( '\TUTOR\Icon' ) && defined( '\TUTOR\Icon::INTERACTIVE_QUIZ' ) ? \TUTOR\Icon::INTERACTIVE_QUIZ : '',
                'auth_cap' => tutor()->instructor_role,
            );
        }
        return $items;
    }

    public static function template( $template ) {
        global $wp_query;
        if ( self::SLUG === ( $wp_query->query_vars['tutor_dashboard_page'] ?? '' ) && self::enabled() ) {
            return EDUQODE_APP_DIR . 'templates/dashboard-h5p.php';
        }
        return $template;
    }

    /** The exercises shown on the page: own ones (an admin sees all). */
    public static function contents( $user_id ) {
        global $wpdb;
        $all  = user_can( $user_id, 'edit_others_h5p_contents' );
        $rows = $wpdb->get_results(
            "SELECT c.id, c.title, c.user_id, c.updated_at, l.title AS type
             FROM {$wpdb->prefix}h5p_contents c LEFT JOIN {$wpdb->prefix}h5p_libraries l ON l.id = c.library_id" .
            ( $all ? '' : $wpdb->prepare( ' WHERE c.user_id = %d', $user_id ) ) . ' ORDER BY c.updated_at DESC LIMIT 500'
        );
        return $rows ?: array();
    }

    /** How many lessons and quiz questions use each exercise: [content id => count]. */
    public static function usage() {
        global $wpdb;
        $n     = array();
        $posts = $wpdb->get_col( "SELECT post_content FROM {$wpdb->posts} WHERE post_status NOT IN ('trash','auto-draft','inherit') AND post_content LIKE '%[h5p%'" );
        foreach ( $posts as $text ) {
            foreach ( EduQode_App_H5P::ids_in_text( $text ) as $id ) {
                $n[ $id ] = ( $n[ $id ] ?? 0 ) + 1;
            }
        }
        $questions = $wpdb->get_results( "SELECT question_description FROM {$wpdb->prefix}tutor_quiz_questions WHERE question_type = 'h5p'" );
        foreach ( (array) $questions as $q ) {
            $id = EduQode_App_H5P::question_content_id( $q );
            if ( $id ) {
                $n[ $id ] = ( $n[ $id ] ?? 0 ) + 1;
            }
        }
        return $n;
    }

    public static function used_in( $content_id ) {
        return self::usage()[ (int) $content_id ] ?? 0;
    }

    // ------------------------------------------------------------ the H5P editor inside the dashboard

    private static function in_frame() {
        return ! empty( $_GET['eduqode_frame'] ) && is_admin() && 'h5p_new' === ( $_GET['page'] ?? '' ); // phpcs:ignore
    }

    /** Tutor LMS Pro may close wp-admin for instructors; the H5P editor page stays open for them. */
    public static function frame_access() {
        if ( ! self::in_frame() || ! self::enabled() || ! self::is_instructor( get_current_user_id() ) ) {
            return;
        }
        global $wp_filter;
        if ( empty( $wp_filter['admin_init'] ) ) {
            return;
        }
        foreach ( $wp_filter['admin_init']->callbacks as $priority => $callbacks ) {
            foreach ( $callbacks as $cb ) {
                $f = $cb['function'];
                if ( is_array( $f ) && is_object( $f[0] ) && 'restrict_wp_admin_area' === $f[1] && 0 === strpos( get_class( $f[0] ), 'TUTOR' ) ) {
                    remove_action( 'admin_init', $f, $priority );
                }
            }
        }
    }

    public static function frame_style() {
        if ( ! self::in_frame() ) {
            return;
        }
        echo '<style id="eduqode-h5p-frame">html.wp-toolbar{padding-top:0!important}#wpadminbar,#adminmenumain,#adminmenuback,#adminmenuwrap,#wpfooter,#screen-meta-links,.update-nag,.notice:not(.error):not(.updated),#wpbody-content>.wrap>h1 .page-title-action{display:none!important}' .
            '#wpcontent,#wpfooter{margin-left:0!important}#wpbody-content{padding-bottom:20px}.wrap{margin:10px 16px 0}#post-body.columns-2 #postbox-container-1{margin-right:0}</style>';
    }

    /** After saving or deleting in the editor, go to a small page that tells the dashboard (not to wp-admin). */
    public static function frame_redirect( $location ) {
        if ( ! self::in_frame() ) {
            return $location;
        }
        if ( preg_match( '/page=h5p&task=show&id=(\d+)/', (string) $location, $m ) ) {
            return add_query_arg( array( 'eduqode_h5p_saved' => (int) $m[1] ), home_url( '/' ) );
        }
        if ( preg_match( '/admin\.php\?page=h5p$/', (string) $location ) ) {
            return add_query_arg( array( 'eduqode_h5p_saved' => 0 ), home_url( '/' ) );
        }
        return $location;
    }

    /** Runs inside the editor frame: tells the dashboard page that the exercise was saved. */
    public static function saved_page() {
        if ( ! isset( $_GET['eduqode_h5p_saved'] ) ) { // phpcs:ignore
            return;
        }
        $id   = absint( $_GET['eduqode_h5p_saved'] ); // phpcs:ignore
        $back = self::url( $id ? array( 'saved' => $id ) : array() );
        nocache_headers();
        echo '<!doctype html><meta charset="utf-8"><script>' .
            'try{if(window.parent!==window){window.parent.postMessage({eduqodeH5P:"saved",id:' . (int) $id . '},' . wp_json_encode( home_url() ) . '.replace(/^(https?:\/\/[^\/]+).*$/,"$1"));}else{location.href=' . wp_json_encode( $back ) . ';}}catch(e){location.href=' . wp_json_encode( $back ) . ';}' .
            '</script><p><a href="' . esc_url( $back ) . '">' . esc_html( eqa__( 'Saved. Back to my exercises' ) ) . '</a></p>';
        exit;
    }

    // ------------------------------------------------------------ course builder lists

    /**
     * The exercises the course builder offers: an admin sees everyone's (with the author's name),
     * an instructor their own. Same content type limits as Tutor LMS Pro.
     */
    public static function builder_rows( $user_id, $search, array $skip_types ) {
        global $wpdb;
        $all  = user_can( $user_id, 'edit_others_h5p_contents' );
        $rows = $wpdb->get_results(
            "SELECT c.id, c.title, l.title AS content_type, u.display_name AS user_name, '' AS tags, c.updated_at, c.user_id
             FROM {$wpdb->prefix}h5p_contents c
             LEFT JOIN {$wpdb->prefix}h5p_libraries l ON l.id = c.library_id
             LEFT JOIN {$wpdb->users} u ON u.ID = c.user_id" .
            ( $all ? '' : $wpdb->prepare( ' WHERE c.user_id = %d', $user_id ) ) . ' ORDER BY c.updated_at DESC LIMIT 1000'
        );
        $search = mb_strtolower( trim( (string) $search ) );
        $out    = array();
        foreach ( (array) $rows as $r ) {
            if ( in_array( (string) $r->content_type, $skip_types, true ) ) {
                continue;
            }
            if ( '' !== $search && false === mb_strpos( mb_strtolower( $r->title . ' ' . $r->content_type ), $search ) ) {
                continue;
            }
            if ( (int) $r->user_id !== (int) $user_id && $r->user_name ) {
                $r->title .= ' — ' . $r->user_name;
            }
            $out[] = $r;
        }
        return $out;
    }

    private static function builder_reply( array $skip_types ) {
        if ( ! self::enabled() || ! function_exists( 'tutor_utils' ) ) {
            return;   // let Tutor LMS Pro answer as usual
        }
        tutor_utils()->checking_nonce();
        $uid = get_current_user_id();
        if ( ! user_can( $uid, 'edit_h5p_contents' ) && ! user_can( $uid, 'edit_others_h5p_contents' ) ) {
            return;
        }
        $search = isset( $_POST['search_filter'] ) ? sanitize_text_field( wp_unslash( $_POST['search_filter'] ) ) : ''; // phpcs:ignore
        wp_send_json_success( array( 'output' => self::builder_rows( $uid, $search, $skip_types ) ) );
    }

    public static function builder_list_lesson() {
        $skip = array( 'Personality Quiz' );
        if ( tutor_utils()->get_option( 'disable_complete_lesson_button' ) ) {
            $skip[] = 'Interactive Book';
        }
        self::builder_reply( $skip );
    }

    public static function builder_list_quiz() {
        self::builder_reply( array( 'Game Map', 'Question Set', 'Interactive Book', 'Interactive Video', 'Course Presentation', 'Personality Quiz' ) );
    }

    // ------------------------------------------------------------ delete

    /** POST eduqode_h5p_delete=<id> to the dashboard page (not wp-admin, which can be closed for instructors). */
    public static function delete() {
        if ( empty( $_POST['eduqode_h5p_delete'] ) || ! is_user_logged_in() ) { // phpcs:ignore
            return;
        }
        $id = absint( $_POST['eduqode_h5p_delete'] ); // phpcs:ignore
        check_admin_referer( 'eduqode_h5p_delete_' . $id );
        $uid = get_current_user_id();
        global $wpdb;
        $owner = (int) $wpdb->get_var( $wpdb->prepare( "SELECT user_id FROM {$wpdb->prefix}h5p_contents WHERE id = %d", $id ) );
        $can   = $owner && ( user_can( $uid, 'edit_others_h5p_contents' ) || ( $owner === $uid && user_can( $uid, 'edit_h5p_contents' ) ) );
        if ( ! self::enabled() || ! $can ) {
            wp_die( esc_html( eqa__( 'You can only delete your own exercises.' ) ), '', array( 'response' => 403 ) );
        }
        if ( self::used_in( $id ) ) {
            wp_safe_redirect( self::url( array( 'error' => 'in_use' ) ) );
            exit;
        }
        $plugin  = H5P_Plugin::get_instance();
        $core    = $plugin->get_h5p_instance( 'core' );
        $content = $core->loadContent( $id );
        if ( is_array( $content ) ) {
            $plugin->get_h5p_instance( 'storage' )->deletePackage( $content );
            $wpdb->delete( $wpdb->prefix . 'h5p_contents_tags', array( 'content_id' => $id ) );
        }
        wp_safe_redirect( self::url( array( 'deleted' => 1 ) ) );
        exit;
    }
}
