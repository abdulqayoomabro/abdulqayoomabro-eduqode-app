<?php
/**
 * REST API for the app. Namespace: /wp-json/eduqode/v1
 *
 *  GET  /me                       logged-in student
 *  GET  /courses                  enrolled courses
 *  GET  /courses/{id}/package     full offline pack (enrolled students only)
 *  GET  /courses/{id}/progress    current progress on the server
 *  POST /sync                     offline progress events
 *  POST /auth/revoke              log this app token out
 *
 * Every route needs the app token (X-EduQode-Token header).
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Rest {

    const NS = 'eduqode/v1';

    public static function init() {
        add_action( 'rest_api_init', array( __CLASS__, 'routes' ) );
        add_filter( 'rest_post_dispatch', array( __CLASS__, 'no_cache' ), 10, 3 );
    }

    public static function routes() {
        $auth = array( 'EduQode_App_Auth', 'require_token' );

        register_rest_route(
            self::NS,
            '/app-config',
            array(
                'methods'             => 'GET',
                'callback'            => array( 'EduQode_App_Config', 'data' ),
                'permission_callback' => '__return_true',
            )
        );
        register_rest_route(
            self::NS,
            '/me',
            array(
                'methods'             => 'GET',
                'callback'            => array( __CLASS__, 'me' ),
                'permission_callback' => $auth,
            )
        );
        register_rest_route(
            self::NS,
            '/courses',
            array(
                'methods'             => 'GET',
                'callback'            => array( __CLASS__, 'courses' ),
                'permission_callback' => $auth,
            )
        );
        register_rest_route(
            self::NS,
            '/courses/(?P<id>\d+)/package',
            array(
                'methods'             => 'GET',
                'callback'            => array( __CLASS__, 'package' ),
                'permission_callback' => $auth,
                'args'                => array( 'id' => array( 'sanitize_callback' => 'absint' ) ),
            )
        );
        register_rest_route(
            self::NS,
            '/courses/(?P<id>\d+)/progress',
            array(
                'methods'             => 'GET',
                'callback'            => array( __CLASS__, 'progress' ),
                'permission_callback' => $auth,
                'args'                => array( 'id' => array( 'sanitize_callback' => 'absint' ) ),
            )
        );
        register_rest_route(
            self::NS,
            '/sync',
            array(
                'methods'             => 'POST',
                'callback'            => array( __CLASS__, 'sync' ),
                'permission_callback' => $auth,
            )
        );
        register_rest_route(
            self::NS,
            '/auth/revoke',
            array(
                'methods'             => 'POST',
                'callback'            => array( __CLASS__, 'revoke' ),
                'permission_callback' => $auth,
            )
        );
    }

    /** Student data must never be cached by SiteGround or any other cache. */
    public static function no_cache( $response, $server, $request ) {
        if ( 0 === strpos( $request->get_route(), '/' . self::NS ) && $response instanceof WP_REST_Response ) {
            $response->header( 'Cache-Control', 'no-store, private, max-age=0' );
        }
        return $response;
    }

    private static function tutor_missing() {
        return new WP_Error( 'eduqode_no_tutor', 'Tutor LMS is not active.', array( 'status' => 503 ) );
    }

    public static function me() {
        $user = wp_get_current_user();
        return array(
            'id'          => $user->ID,
            'name'        => $user->display_name,
            'email'       => $user->user_email,
            'server_time' => time() * 1000,
            'offline'     => EduQode_App_Courses::offline_enabled(),
            'version'     => EDUQODE_APP_VERSION,
        );
    }

    public static function courses() {
        if ( ! eduqode_app_tutor_ready() ) {
            return self::tutor_missing();
        }
        $user_id = get_current_user_id();
        $list    = array();
        foreach ( EduQode_App_Courses::enrolled_course_ids( $user_id ) as $course_id ) {
            if ( 'publish' === get_post_status( $course_id ) ) {
                $list[] = EduQode_App_Courses::course_summary( $course_id, $user_id );
            }
        }
        return array(
            'courses'     => $list,
            'server_time' => time() * 1000,
        );
    }

    private static function check_course( $course_id, $user_id ) {
        if ( ! eduqode_app_tutor_ready() ) {
            return self::tutor_missing();
        }
        if ( ! EduQode_App_Courses::can_access( $course_id, $user_id ) ) {
            return new WP_Error( 'eduqode_no_access', 'You are not enrolled in this course, or your access has ended.', array( 'status' => 403 ) );
        }
        return true;
    }

    public static function package( $request ) {
        $user_id   = get_current_user_id();
        $course_id = (int) $request['id'];
        $check     = self::check_course( $course_id, $user_id );
        if ( is_wp_error( $check ) ) {
            return $check;
        }
        if ( ! EduQode_App_Courses::offline_enabled() ) {
            return new WP_Error( 'eduqode_offline_off', 'Offline downloads are switched off.', array( 'status' => 403 ) );
        }
        return EduQode_App_Courses::package( $course_id, $user_id );
    }

    public static function progress( $request ) {
        $user_id   = get_current_user_id();
        $course_id = (int) $request['id'];
        $check     = self::check_course( $course_id, $user_id );
        if ( is_wp_error( $check ) ) {
            return $check;
        }
        $sync = new EduQode_App_Sync( $user_id );
        return $sync->progress( $course_id );
    }

    public static function sync( $request ) {
        if ( ! eduqode_app_tutor_ready() ) {
            return self::tutor_missing();
        }
        $body   = $request->get_json_params();
        $events = isset( $body['events'] ) && is_array( $body['events'] ) ? $body['events'] : array();
        $sync   = new EduQode_App_Sync( get_current_user_id() );
        $result = $sync->apply( $events );

        // Courses the app asked about without sending events (pull-only refresh).
        $extra = isset( $body['course_ids'] ) && is_array( $body['course_ids'] ) ? array_map( 'intval', $body['course_ids'] ) : array();
        $known = wp_list_pluck( $result['progress'], 'course_id' );
        foreach ( array_slice( array_unique( $extra ), 0, 50 ) as $course_id ) {
            if ( in_array( $course_id, $known, true ) ) {
                continue;
            }
            if ( EduQode_App_Courses::can_access( $course_id, get_current_user_id() ) ) {
                $result['progress'][] = $sync->progress( $course_id );
            } else {
                $result['no_access'][] = $course_id;
            }
        }
        $result['server_time'] = time() * 1000;
        return $result;
    }

    public static function revoke( $request ) {
        EduQode_App_Auth::revoke( EduQode_App_Auth::token_from_request( $request ) );
        return array( 'ok' => true );
    }
}
