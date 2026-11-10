<?php
/**
 * Plugin Name: EduQode App Connector
 * Description: Lets the EduQode Android app log in with Google. Google login runs in a browser tab and this plugin hands the login back to the app. Not needed for anything else; the app hides prices and buy buttons by itself.
 * Version: 1.0
 * Author: EduQode
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

function eduqode_is_app() {
    $ua = isset( $_SERVER['HTTP_USER_AGENT'] ) ? $_SERVER['HTTP_USER_AGENT'] : '';
    return strpos( $ua, 'EduQodeApp' ) !== false;
}

/* ------------------------------------------------------------------
 * Google login for the app
 *
 * Google does not allow its login screen inside an app's web view, so:
 * 1. The app opens  https://lms.eduqode.com/?eduqode_app_handoff=1  in a Chrome tab.
 * 2. The user logs in there (with Google or a password) as on the normal website.
 * 3. This code creates a one-time token (valid 2 minutes) and sends the user back
 *    to the app with  eduqode://auth?token=...
 * 4. The app opens  ?eduqode_app_token=...  and this code logs the app in.
 * ------------------------------------------------------------------ */

// The page where students log in. Change it if your login page moves.
function eduqode_app_login_page_url() {
    return home_url( '/tutor-login-3/' );
}

function eduqode_app_send_back( $user_id ) {
    $token = wp_generate_password( 40, false, false );
    set_transient( 'eduqode_app_tok_' . $token, (int) $user_id, 2 * MINUTE_IN_SECONDS );

    // The hand-off is finished, so the reminder cookie is no longer needed.
    setcookie( 'eduqode_app_handoff', '', time() - 3600, COOKIEPATH ? COOKIEPATH : '/', COOKIE_DOMAIN, is_ssl(), true );

    $app_link = 'eduqode://auth?token=' . rawurlencode( $token );
    nocache_headers();
    header( 'Content-Type: text/html; charset=utf-8' );
    ?>
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Return to the EduQode app</title>
<style>
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
         background:#000; color:#fff; font-family:system-ui,-apple-system,Roboto,sans-serif; text-align:center; }
  .box { padding:32px 24px; max-width:340px; }
  h1 { font-size:22px; margin:0 0 8px; }
  p { color:#bbb; line-height:1.5; margin:0 0 24px; }
  a { display:inline-block; background:#F5A623; color:#000; text-decoration:none; font-weight:600;
      padding:14px 32px; border-radius:999px; }
</style>
</head>
<body>
  <div class="box">
    <h1>You are logged in</h1>
    <p>Tap the button to go back to the EduQode app.</p>
    <a id="open" href="<?php echo esc_attr( $app_link ); ?>">Open the EduQode app</a>
  </div>
  <script>setTimeout(function () { window.location.href = <?php echo wp_json_encode( $app_link ); ?>; }, 300);</script>
</body>
</html>
    <?php
    exit;
}

add_action( 'init', function () {
    $is_page_request = 'GET' === ( isset( $_SERVER['REQUEST_METHOD'] ) ? $_SERVER['REQUEST_METHOD'] : '' )
        && ! wp_doing_ajax()
        && false === strpos( isset( $_SERVER['REQUEST_URI'] ) ? $_SERVER['REQUEST_URI'] : '', '/wp-json/' );

    // Step 4: the app arrives with a one-time token and gets logged in.
    if ( isset( $_GET['eduqode_app_token'] ) ) {
        $token   = preg_replace( '/[^A-Za-z0-9]/', '', (string) wp_unslash( $_GET['eduqode_app_token'] ) );
        $user_id = $token ? (int) get_transient( 'eduqode_app_tok_' . $token ) : 0;
        if ( $token ) {
            delete_transient( 'eduqode_app_tok_' . $token ); // usable only once
        }
        if ( $user_id > 0 && get_userdata( $user_id ) ) {
            wp_clear_auth_cookie();
            wp_set_current_user( $user_id );
            wp_set_auth_cookie( $user_id, true, is_ssl() );
            wp_safe_redirect( home_url( '/dashboard/' ) );
        } else {
            wp_safe_redirect( eduqode_app_login_page_url() );
        }
        exit;
    }

    // Step 1 to 3: hand-off page opened by the app in the Chrome tab.
    if ( isset( $_GET['eduqode_app_handoff'] ) && $is_page_request ) {
        if ( is_user_logged_in() ) {
            eduqode_app_send_back( get_current_user_id() );
        }
        // Not logged in yet: remember the hand-off, then show the normal login page.
        setcookie( 'eduqode_app_handoff', '1', time() + 15 * MINUTE_IN_SECONDS, COOKIEPATH ? COOKIEPATH : '/', COOKIE_DOMAIN, is_ssl(), true );
        wp_safe_redirect( add_query_arg( 'redirect_to', rawurlencode( home_url( '/?eduqode_app_handoff=1' ) ), eduqode_app_login_page_url() ) );
        exit;
    }

    // Some login plugins ignore redirect_to. If the user is now logged in in the Chrome tab
    // and a hand-off is pending, send them back to the app from whatever page they landed on.
    if ( $is_page_request && is_user_logged_in() && ! empty( $_COOKIE['eduqode_app_handoff'] ) && ! eduqode_is_app() ) {
        eduqode_app_send_back( get_current_user_id() );
    }
}, 1 );
