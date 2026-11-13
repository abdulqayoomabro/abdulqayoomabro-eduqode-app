<?php
/**
 * Settings > EduQode App
 *  - switch offline downloads on/off
 *  - test mode: one test MP4 for chosen lesson IDs
 *  - offline video link (MP4) per lesson, course by course
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Admin {

    const SLUG = 'eduqode-app';

    public static function init() {
        add_action( 'admin_menu', array( __CLASS__, 'menu' ) );
        add_action( 'admin_post_eduqode_app_save', array( __CLASS__, 'save' ) );
        add_filter( 'plugin_action_links_eduqode-app-connector/eduqode-app-connector.php', array( __CLASS__, 'links' ) );
    }

    public static function links( $links ) {
        array_unshift( $links, '<a href="' . esc_url( admin_url( 'options-general.php?page=' . self::SLUG ) ) . '">' . esc_html( eqa__( 'Settings' ) ) . '</a>' );
        return $links;
    }

    public static function menu() {
        add_options_page( eqa__( 'EduQode App' ), eqa__( 'EduQode App' ), 'manage_options', self::SLUG, array( __CLASS__, 'page' ) );
    }

    public static function save() {
        if ( ! current_user_can( 'manage_options' ) ) {
            wp_die( eqa__( 'Not allowed.' ) );
        }
        check_admin_referer( 'eduqode_app_save' );

        $section = isset( $_POST['section'] ) ? sanitize_key( $_POST['section'] ) : '';
        $course  = isset( $_POST['course'] ) ? absint( $_POST['course'] ) : 0;

        if ( 'general' === $section ) {
            update_option( 'eduqode_app_offline_enabled', empty( $_POST['offline_enabled'] ) ? '0' : '1' );
            update_option( EduQode_App_H5P_Studio::OPTION, empty( $_POST['instructor_h5p'] ) ? '0' : '1' );
            update_option( EduQode_App_No_Earnings::OPTION, empty( $_POST['hide_earnings'] ) ? '0' : '1' );
            $url = isset( $_POST['test_video_url'] ) ? esc_url_raw( trim( wp_unslash( $_POST['test_video_url'] ) ) ) : '';
            update_option( 'eduqode_app_test_video_url', $url );
            $ids = isset( $_POST['test_lesson_ids'] ) ? wp_unslash( $_POST['test_lesson_ids'] ) : '';
            $ids = implode( ',', array_filter( array_map( 'absint', preg_split( '/[\s,]+/', $ids, -1, PREG_SPLIT_NO_EMPTY ) ) ) );
            update_option( 'eduqode_app_test_lesson_ids', $ids );
        }

        if ( 'app' === $section ) {
            EduQode_App_Config::save( $_POST );
        }

        if ( 'videos' === $section && $course && isset( $_POST['video'] ) && is_array( $_POST['video'] ) && eduqode_app_tutor_ready() ) {
            foreach ( wp_unslash( $_POST['video'] ) as $lesson_id => $url ) {
                $lesson_id = absint( $lesson_id );
                if ( ! $lesson_id || ! EduQode_App_Courses::lesson_in_course( $lesson_id, $course ) ) {
                    continue;
                }
                $url = esc_url_raw( trim( (string) $url ) );
                if ( $url ) {
                    update_post_meta( $lesson_id, EduQode_App_Courses::VIDEO_META, $url );
                } else {
                    delete_post_meta( $lesson_id, EduQode_App_Courses::VIDEO_META );
                }
            }
        }

        $args = array( 'page' => self::SLUG, 'saved' => 1 );
        if ( $course ) {
            $args['course'] = $course;
        }
        wp_safe_redirect( add_query_arg( $args, admin_url( 'options-general.php' ) ) );
        exit;
    }

    public static function page() {
        if ( ! current_user_can( 'manage_options' ) ) {
            return;
        }
        $enabled  = EduQode_App_Courses::offline_enabled();
        $test_url = get_option( 'eduqode_app_test_video_url', '' );
        $test_ids = get_option( 'eduqode_app_test_lesson_ids', '' );
        $course   = isset( $_GET['course'] ) ? absint( $_GET['course'] ) : 0;
        ?>
        <div class="wrap">
            <h1><?php echo esc_html( eqa__( 'EduQode App' ) ); ?> <small style="font-size:13px;color:#646970"><?php echo esc_html( eqa__( 'plugin version' ) ); ?> <?php echo esc_html( EDUQODE_APP_VERSION ); ?></small></h1>
            <?php if ( ! empty( $_GET['saved'] ) ) : ?>
                <div class="notice notice-success is-dismissible"><p><?php echo esc_html( eqa__( 'Saved.' ) ); ?></p></div>
            <?php endif; ?>
            <?php if ( ! eduqode_app_tutor_ready() ) : ?>
                <div class="notice notice-error"><p><?php echo esc_html( eqa__( 'Tutor LMS is not active. Offline packs need Tutor LMS.' ) ); ?></p></div>
            <?php endif; ?>

            <h2><?php echo esc_html( eqa__( 'Offline downloads' ) ); ?></h2>
            <form method="post" action="<?php echo esc_url( admin_url( 'admin-post.php' ) ); ?>">
                <?php wp_nonce_field( 'eduqode_app_save' ); ?>
                <input type="hidden" name="action" value="eduqode_app_save">
                <input type="hidden" name="section" value="general">
                <table class="form-table" role="presentation">
                    <tr>
                        <th scope="row"><?php echo esc_html( eqa__( 'Offline packs' ) ); ?></th>
                        <td><label><input type="checkbox" name="offline_enabled" value="1" <?php checked( $enabled ); ?>> <?php echo esc_html( eqa__( 'Students can download enrolled courses in the app' ) ); ?></label></td>
                    </tr>
                    <tr>
                        <th scope="row"><?php echo esc_html( eqa__( 'H5P for instructors' ) ); ?></th>
                        <td><label><input type="checkbox" name="instructor_h5p" value="1" <?php checked( '1' === get_option( EduQode_App_H5P_Studio::OPTION, '1' ) ); ?>> <?php echo esc_html( eqa__( 'Instructors can make H5P exercises from their Tutor LMS dashboard' ) ); ?></label>
                        <p class="description"><?php echo esc_html( eqa__( 'Only their own exercises, and only with content types installed here (H5P Content → Libraries).' ) ); ?></p></td>
                    </tr>
                    <tr>
                        <th scope="row"><?php echo esc_html( eqa__( 'Company training' ) ); ?></th>
                        <td><label><input type="checkbox" name="hide_earnings" value="1" <?php checked( EduQode_App_No_Earnings::enabled() ); ?>> <?php echo esc_html( eqa__( 'Hide earnings, withdrawals and billing in the Tutor LMS dashboard' ) ); ?></label>
                        <p class="description"><?php echo esc_html( eqa__( 'For everyone in the front-end dashboard, admins too (Tutor LMS reports in wp-admin stay). Also hides Tutor LMS Pro\'s Analytics menu, which is mostly about earnings.' ) ); ?></p></td>
                    </tr>
                    <tr>
                        <th scope="row"><label for="eq-test-url"><?php echo esc_html( eqa__( 'Test video URL' ) ); ?></label></th>
                        <td>
                            <input type="url" class="regular-text" id="eq-test-url" name="test_video_url" value="<?php echo esc_attr( $test_url ); ?>" placeholder="https://example.com/video.mp4">
                            <p class="description"><?php echo esc_html( eqa__( 'A direct MP4 link you own or are allowed to distribute. Used only for the lessons listed below, so you can test offline video before moving real videos off YouTube.' ) ); ?></p>
                        </td>
                    </tr>
                    <tr>
                        <th scope="row"><label for="eq-test-ids"><?php echo esc_html( eqa__( 'Use test video on lesson IDs' ) ); ?></label></th>
                        <td>
                            <input type="text" class="regular-text" id="eq-test-ids" name="test_lesson_ids" value="<?php echo esc_attr( $test_ids ); ?>" placeholder="123, 456">
                            <p class="description"><?php echo esc_html( eqa__( 'Lesson IDs are shown in the table below.' ) ); ?></p>
                        </td>
                    </tr>
                </table>
                <?php submit_button( eqa__( 'Save settings' ) ); ?>
            </form>

            <hr>
            <h2><?php echo esc_html( eqa__( 'App settings' ) ); ?></h2>
            <p><?php echo esc_html( eqa__( 'The Android app reads these settings each time it starts (no app update needed).' ) ); ?></p>
            <?php $cfg = EduQode_App_Config::data(); ?>
            <form method="post" action="<?php echo esc_url( admin_url( 'admin-post.php' ) ); ?>">
                <?php wp_nonce_field( 'eduqode_app_save' ); ?>
                <input type="hidden" name="action" value="eduqode_app_save">
                <input type="hidden" name="section" value="app">
                <table class="form-table" role="presentation">
                    <tr>
                        <th scope="row"><?php echo esc_html( eqa__( 'Google login in the app' ) ); ?></th>
                        <td>
                            <select name="google_login">
                                <option value="off" <?php selected( $cfg['config']['googleLogin'], 'off' ); ?>><?php echo esc_html( eqa__( 'Off (show a message)' ) ); ?></option>
                                <option value="handoff" <?php selected( $cfg['config']['googleLogin'], 'handoff' ); ?>><?php echo esc_html( eqa__( 'On (log in through a browser tab)' ) ); ?></option>
                            </select>
                        </td>
                    </tr>
                    <tr>
                        <th scope="row"><?php echo esc_html( eqa__( 'Pages blocked in the app' ) ); ?></th>
                        <td><textarea name="blocked_paths" rows="4" class="large-text code"><?php echo esc_textarea( implode( "\n", $cfg['config']['blockedPathParts'] ) ); ?></textarea>
                            <p class="description"><?php echo esc_html( eqa__( 'One per line, e.g. /cart. Links containing these open nowhere in the app.' ) ); ?></p></td>
                    </tr>
                    <tr>
                        <th scope="row"><?php echo esc_html( eqa__( 'Blocked link parameters' ) ); ?></th>
                        <td><textarea name="blocked_query" rows="3" class="large-text code"><?php echo esc_textarea( implode( "\n", $cfg['config']['blockedQueryParts'] ) ); ?></textarea></td>
                    </tr>
                    <tr>
                        <th scope="row"><?php echo esc_html( eqa__( 'CSS added to pages in the app' ) ); ?></th>
                        <td><textarea name="app_css" rows="10" class="large-text code"><?php echo esc_textarea( $cfg['css'] ); ?></textarea>
                            <p class="description"><?php echo esc_html( eqa__( 'Used to hide prices and buy buttons inside the app. Applies only inside the app.' ) ); ?></p></td>
                    </tr>
                </table>
                <?php submit_button( eqa__( 'Save app settings' ) ); ?>
            </form>

            <hr>
            <h2><?php echo esc_html( eqa__( 'Offline video links' ) ); ?></h2>
            <p><?php echo esc_html( eqa__( 'YouTube and Vimeo videos stay online-only. To make a lesson\'s video available offline, paste a direct MP4 link that you own (your server, Bunny, S3, ...). Videos uploaded to Tutor as "HTML5 (mp4)" are offline automatically.' ) ); ?></p>
            <p><strong><?php echo esc_html( eqa__( 'Google Drive:' ) ); ?></strong> <?php echo esc_html( eqa__( 'paste the share link of the video file (not a folder), shared as "Anyone with the link". Good for testing and small groups; Drive blocks a file for about 24 hours when it is downloaded too many times.' ) ); ?></p>
            <?php if ( eduqode_app_tutor_ready() ) : ?>
                <form method="get">
                    <input type="hidden" name="page" value="<?php echo esc_attr( self::SLUG ); ?>">
                    <select name="course">
                        <option value=""><?php echo esc_html( eqa__( 'Choose a course' ) ); ?></option>
                        <?php
                        $courses = get_posts(
                            array(
                                'post_type'      => tutor()->course_post_type,
                                'post_status'    => array( 'publish', 'draft', 'private' ),
                                'posts_per_page' => -1,
                                'orderby'        => 'title',
                                'order'          => 'ASC',
                            )
                        );
                        foreach ( $courses as $c ) {
                            printf( '<option value="%d" %s>%s</option>', (int) $c->ID, selected( $course, $c->ID, false ), esc_html( get_the_title( $c ) ) );
                        }
                        ?>
                    </select>
                    <?php submit_button( eqa__( 'Show lessons' ), 'secondary', '', false ); ?>
                </form>
                <?php if ( $course ) : ?>
                    <form method="post" action="<?php echo esc_url( admin_url( 'admin-post.php' ) ); ?>">
                        <?php wp_nonce_field( 'eduqode_app_save' ); ?>
                        <input type="hidden" name="action" value="eduqode_app_save">
                        <input type="hidden" name="section" value="videos">
                        <input type="hidden" name="course" value="<?php echo (int) $course; ?>">
                        <table class="widefat striped" style="margin-top:12px">
                            <thead><tr><th><?php echo esc_html( eqa__( 'ID' ) ); ?></th><th><?php echo esc_html( eqa__( 'Lesson' ) ); ?></th><th><?php echo esc_html( eqa__( 'Website video' ) ); ?></th><th><?php echo esc_html( eqa__( 'Offline video (MP4 link)' ) ); ?></th><th><?php echo esc_html( eqa__( 'Offline status' ) ); ?></th></tr></thead>
                            <tbody>
                            <?php
                            foreach ( EduQode_App_Courses::structure( $course ) as $topic ) {
                                printf( '<tr><td colspan="5"><strong>%s</strong></td></tr>', esc_html( get_the_title( $topic['post'] ) ) );
                                foreach ( $topic['items'] as $item ) {
                                    if ( $item->post_type !== tutor()->lesson_post_type ) {
                                        printf( '<tr><td>%d</td><td>%s</td><td colspan="2">' . esc_html( eqa__( 'Quiz / other content' ) ) . '</td><td>' . esc_html( eqa__( 'Online only' ) ) . '</td></tr>', (int) $item->ID, esc_html( get_the_title( $item ) ) );
                                        continue;
                                    }
                                    $video  = EduQode_App_Courses::video( $item->ID, get_current_user_id() );
                                    $online = $video['online'] ? $video['online']['provider'] : 'none';
                                    $status = $video['offline'] ? sprintf( eqa__( 'Offline (%s)' ), $video['offline']['origin'] ) : ( $video['online'] ? eqa__( 'Online only' ) : eqa__( 'No video' ) );
                                    $link   = (string) get_post_meta( $item->ID, EduQode_App_Courses::VIDEO_META, true );
                                    if ( false !== strpos( $link, 'drive.google.com/drive/folders' ) ) {
                                        $status = eqa__( 'Folder link: paste the video file link instead' );
                                    } elseif ( false !== strpos( $link, 'google.com' ) ) {
                                        $status .= ' · Google Drive';
                                    }
                                    printf(
                                        '<tr><td>%1$d</td><td>%2$s</td><td>%3$s</td><td><input type="url" class="large-text" name="video[%1$d]" value="%4$s" placeholder="' . esc_attr( eqa__( 'https://.../lesson.mp4' ) ) . '"></td><td>%5$s</td></tr>',
                                        (int) $item->ID,
                                        esc_html( get_the_title( $item ) ),
                                        esc_html( $online ),
                                        esc_attr( get_post_meta( $item->ID, EduQode_App_Courses::VIDEO_META, true ) ),
                                        esc_html( $status )
                                    );
                                }
                            }
                            ?>
                            </tbody>
                        </table>
                        <?php submit_button( eqa__( 'Save offline video links' ) ); ?>
                    </form>
                <?php endif; ?>
            <?php endif; ?>
        </div>
        <?php
    }
}
