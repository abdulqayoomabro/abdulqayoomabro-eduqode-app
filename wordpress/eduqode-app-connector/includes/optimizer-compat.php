<?php
/**
 * Keeps speed-optimizer plugins from rewriting Tutor LMS and H5P scripts.
 *
 * Tutor LMS 4 loads parts of its JavaScript on demand ("lazy chunks") from its own plugin
 * folder. When SiteGround Speed Optimizer minifies or combines tutor-core.min.js into
 * wp-content/uploads/..., Tutor looks for those parts in the wrong folder
 * ("ChunkLoadError ... uploads/js/lazy-chunks/tutor-form.js") and re-minifying can also
 * break its menus and popups. These filters leave Tutor, Tutor Pro and H5P scripts untouched
 * on the website and in the app. Other scripts are still optimized.
 */

defined( 'ABSPATH' ) || exit;

/** Script handles of Tutor LMS, Tutor Pro and H5P that are registered on this page. */
function eduqode_app_protected_script_handles() {
	global $wp_scripts;
	$handles = array();
	if ( ! $wp_scripts instanceof WP_Scripts ) {
		return $handles;
	}
	foreach ( $wp_scripts->registered as $handle => $script ) {
		$src = is_string( $script->src ) ? $script->src : '';
		if ( 0 === strpos( $handle, 'tutor' ) || 0 === strpos( $handle, 'h5p' )
			|| false !== strpos( $src, '/plugins/tutor/' ) || false !== strpos( $src, '/plugins/tutor-pro/' )
			|| false !== strpos( $src, '/plugins/h5p/' ) ) {
			$handles[] = $handle;
		}
	}
	return $handles;
}

function eduqode_app_exclude_protected_scripts( $exclude ) {
	if ( ! get_option( 'eduqode_app_protect_tutor_js', 1 ) ) {
		return $exclude;
	}
	$exclude = is_array( $exclude ) ? $exclude : array();
	return array_values( array_unique( array_merge( $exclude, eduqode_app_protected_script_handles() ) ) );
}

// SiteGround Speed Optimizer: minify, combine and defer.
add_filter( 'sgo_js_minify_exclude', 'eduqode_app_exclude_protected_scripts' );
add_filter( 'sgo_javascript_combine_exclude', 'eduqode_app_exclude_protected_scripts' );
add_filter( 'sgo_js_async_exclude', 'eduqode_app_exclude_protected_scripts' );
