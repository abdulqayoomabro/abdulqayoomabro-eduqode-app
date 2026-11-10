<?php
/**
 * EduQode App: hide prices and buy buttons inside the Android app.
 *
 * How to use: install the free "Code Snippets" plugin, add a new PHP snippet,
 * paste everything below this comment block (without the opening <?php line),
 * set it to "Run everywhere" and activate it.
 *
 * It only affects visitors whose browser identifies as "EduQodeApp" (the app).
 * The normal website stays exactly the same.
 */

function eduqode_is_app() {
    $ua = isset( $_SERVER['HTTP_USER_AGENT'] ) ? $_SERVER['HTTP_USER_AGENT'] : '';
    return strpos( $ua, 'EduQodeApp' ) !== false;
}

// Adds an "in-app" class to <body> so the CSS below only applies inside the app.
add_filter( 'body_class', function ( $classes ) {
    if ( eduqode_is_app() ) {
        $classes[] = 'in-app';
    }
    return $classes;
} );

// Hides prices, buy / enroll buttons and the cart icon inside the app.
// Check class names with right-click > Inspect if something still shows.
add_action( 'wp_head', function () {
    if ( ! eduqode_is_app() ) {
        return;
    }
    ?>
    <style id="eduqode-app-hide-buy">
        .in-app .tutor-course-sidebar-card-pricing,
        .in-app .tutor-course-price,
        .in-app .tutor-course-loop-price,
        .in-app .tutor-add-to-cart-button,
        .in-app .tutor-native-add-to-cart,
        .in-app .tutor-woocommerce-add-to-cart,
        .in-app .tutor-course-sidebar-card-btns .tutor-btn-primary,
        .in-app .single_add_to_cart_button,
        .in-app .add_to_cart_button,
        .in-app .woocommerce-Price-amount,
        .in-app .price,
        .in-app .elementor-menu-cart__wrapper,
        .in-app .elementor-widget-woocommerce-menu-cart,
        .in-app a[href*="add-to-cart"],
        .in-app a[href*="/cart"],
        .in-app a[href*="/checkout"],
        .in-app a[href*="p=28301"] {
            display: none !important;
        }
    </style>
    <?php
} );

// Safety net: if the app somehow lands on cart or checkout, send it to the dashboard.
add_action( 'template_redirect', function () {
    if ( ! eduqode_is_app() ) {
        return;
    }
    $is_cart_page = function_exists( 'is_cart' ) && is_cart();
    $is_checkout  = function_exists( 'is_checkout' ) && is_checkout();
    if ( $is_cart_page || $is_checkout ) {
        wp_safe_redirect( home_url( '/dashboard/' ) );
        exit;
    }
} );
