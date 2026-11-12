<?php
/**
 * App tokens.
 *
 * The app's web view is logged in to WordPress with normal cookies. To call the REST API
 * from native code, the app POSTs once to  /?eduqode_app=issue_token  with those cookies
 * and receives a personal token of the form  "<user_id>.<random>".  Only a hash of the
 * random part is stored. Each user can have up to 5 app tokens (devices); the oldest is
 * dropped. Tokens are sent in the  X-EduQode-Token  header (not "Authorization", which
 * some Apache hosts strip).
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Auth {

    const META        = '_eduqode_app_tokens';
    const MAX_DEVICES = 5;
    const HEADER      = 'HTTP_X_EDUQODE_TOKEN';

    public static function init() {
        add_action( 'init', array( __CLASS__, 'maybe_issue_token' ), 2 );
    }

    private static function hash( $secret ) {
        return hash_hmac( 'sha256', $secret, wp_salt( 'auth' ) );
    }

    /** Front-end endpoint used with the web view's login cookies. */
    public static function maybe_issue_token() {
        if ( ! isset( $_GET['eduqode_app'] ) || 'issue_token' !== $_GET['eduqode_app'] ) {
            return;
        }
        nocache_headers();
        if ( 'POST' !== ( isset( $_SERVER['REQUEST_METHOD'] ) ? $_SERVER['REQUEST_METHOD'] : '' ) ) {
            wp_send_json( array( 'error' => 'method_not_allowed' ), 405 );
        }
        if ( ! is_user_logged_in() ) {
            wp_send_json( array( 'error' => 'not_logged_in' ), 401 );
        }

        $user_id   = get_current_user_id();
        $device_id = isset( $_POST['device_id'] ) ? substr( preg_replace( '/[^A-Za-z0-9-]/', '', wp_unslash( $_POST['device_id'] ) ), 0, 64 ) : '';
        $device    = isset( $_POST['device_name'] ) ? substr( sanitize_text_field( wp_unslash( $_POST['device_name'] ) ), 0, 80 ) : '';

        $token = self::create_token( $user_id, $device_id, $device );
        $user  = get_userdata( $user_id );

        wp_send_json(
            array(
                'token' => $token,
                'user'  => array(
                    'id'    => $user_id,
                    'login' => $user ? $user->user_login : '',
                    'name'  => $user ? $user->display_name : '',
                    'email' => $user ? $user->user_email : '',
                ),
            )
        );
    }

    public static function create_token( $user_id, $device_id = '', $device_name = '' ) {
        $secret = wp_generate_password( 48, false, false );
        $tokens = self::tokens( $user_id );

        // One token per device: a new login on the same device replaces the old token.
        if ( $device_id ) {
            foreach ( $tokens as $hash => $info ) {
                if ( isset( $info['device_id'] ) && $info['device_id'] === $device_id ) {
                    unset( $tokens[ $hash ] );
                }
            }
        }
        $tokens[ self::hash( $secret ) ] = array(
            'device_id' => $device_id,
            'device'    => $device_name,
            'created'   => time(),
            'last_used' => time(),
        );
        // Keep the newest MAX_DEVICES tokens.
        uasort(
            $tokens,
            function ( $a, $b ) {
                return $b['created'] - $a['created'];
            }
        );
        $tokens = array_slice( $tokens, 0, self::MAX_DEVICES, true );
        update_user_meta( $user_id, self::META, $tokens );

        return $user_id . '.' . $secret;
    }

    private static function tokens( $user_id ) {
        $tokens = get_user_meta( $user_id, self::META, true );
        return is_array( $tokens ) ? $tokens : array();
    }

    /** Returns the user ID for a valid token, or 0. */
    public static function user_from_token( $token ) {
        if ( ! is_string( $token ) || ! preg_match( '/^(\d+)\.([A-Za-z0-9]{48})$/', $token, $m ) ) {
            return 0;
        }
        $user_id = (int) $m[1];
        if ( ! get_userdata( $user_id ) ) {
            return 0;
        }
        $tokens = self::tokens( $user_id );
        $hash   = self::hash( $m[2] );
        foreach ( $tokens as $stored => $info ) {
            if ( hash_equals( (string) $stored, $hash ) ) {
                // Update "last used" at most once an hour to avoid a database write per request.
                if ( time() - (int) $info['last_used'] > HOUR_IN_SECONDS ) {
                    $tokens[ $stored ]['last_used'] = time();
                    update_user_meta( $user_id, self::META, $tokens );
                }
                return $user_id;
            }
        }
        return 0;
    }

    public static function revoke( $token ) {
        if ( ! preg_match( '/^(\d+)\.([A-Za-z0-9]{48})$/', (string) $token, $m ) ) {
            return;
        }
        $tokens = self::tokens( (int) $m[1] );
        unset( $tokens[ self::hash( $m[2] ) ] );
        update_user_meta( (int) $m[1], self::META, $tokens );
    }

    public static function token_from_request( $request ) {
        $token = $request->get_header( 'x_eduqode_token' );
        if ( ! $token && isset( $_SERVER[ self::HEADER ] ) ) {
            $token = wp_unslash( $_SERVER[ self::HEADER ] );
        }
        return is_string( $token ) ? trim( $token ) : '';
    }

    /** REST permission callback: valid app token required. Sets the current user. */
    public static function require_token( $request ) {
        $user_id = self::user_from_token( self::token_from_request( $request ) );
        if ( ! $user_id ) {
            return new WP_Error( 'eduqode_unauthorized', 'Please log in to the app again.', array( 'status' => 401 ) );
        }
        wp_set_current_user( $user_id );
        // Answers to the app (reasons, notifications, errors) in the student's language.
        // The app says which language it shows ("ur" or "en"); otherwise the student's website language.
        $app_lang = (string) $request->get_header( 'x_eduqode_lang' );
        eqa_use_user_language( $user_id, 'ur' === $app_lang ? 'ur' : ( 'en' === $app_lang ? 'en_US' : null ) );
        return true;
    }
}
