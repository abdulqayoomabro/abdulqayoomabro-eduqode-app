<?php
/**
 * Tools → EduQode Backup: download a backup zip, or restore one (see EduQode_App_Backup).
 * Also a WP-CLI command for large sites:
 *   wp eduqode backup export [--courses=12,15] [--students] [--file=backup.zip]
 *   wp eduqode backup import backup.zip [--no-settings]
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Backup_Admin {

    const SLUG = 'eduqode-backup';

    public static function init() {
        add_action( 'admin_menu', array( __CLASS__, 'menu' ) );
        add_action( 'admin_post_eduqode_backup_export', array( __CLASS__, 'download' ) );
        add_action( 'admin_post_eduqode_backup_import', array( __CLASS__, 'restore' ) );
        if ( defined( 'WP_CLI' ) && WP_CLI ) {
            WP_CLI::add_command( 'eduqode backup', 'EduQode_App_Backup_CLI' );
        }
    }

    public static function menu() {
        add_management_page( 'EduQode Backup', 'EduQode Backup', 'manage_options', self::SLUG, array( __CLASS__, 'page' ) );
    }

    public static function download() {
        if ( ! current_user_can( 'manage_options' ) ) {
            wp_die( 'Not allowed.' );
        }
        check_admin_referer( 'eduqode_backup_export' );
        @set_time_limit( 0 );
        $all     = ! empty( $_POST['all_courses'] );
        $courses = $all ? array() : array_map( 'absint', (array) ( $_POST['courses'] ?? array() ) );
        if ( ! $all && ! $courses ) {
            wp_safe_redirect( admin_url( 'tools.php?page=' . self::SLUG . '&error=' . rawurlencode( 'Choose at least one course.' ) ) );
            exit;
        }
        try {
            $file = EduQode_App_Backup::export( $courses, ! empty( $_POST['students'] ), ! empty( $_POST['settings'] ) );
        } catch ( Exception $e ) {
            wp_safe_redirect( admin_url( 'tools.php?page=' . self::SLUG . '&error=' . rawurlencode( $e->getMessage() ) ) );
            exit;
        }
        $name = 'eduqode-backup-' . gmdate( 'Y-m-d-His' ) . '.zip';
        nocache_headers();
        header( 'Content-Type: application/zip' );
        header( 'Content-Disposition: attachment; filename="' . $name . '"' );
        header( 'Content-Length: ' . filesize( $file ) );
        readfile( $file );
        @unlink( $file );
        exit;
    }

    public static function restore() {
        if ( ! current_user_can( 'manage_options' ) ) {
            wp_die( 'Not allowed.' );
        }
        check_admin_referer( 'eduqode_backup_import' );
        @set_time_limit( 0 );
        $upload = $_FILES['backup'] ?? null;
        if ( ! $upload || UPLOAD_ERR_OK !== (int) $upload['error'] || ! is_uploaded_file( $upload['tmp_name'] ) ) {
            wp_safe_redirect( admin_url( 'tools.php?page=' . self::SLUG . '&error=' . rawurlencode( 'Upload failed. Check the file size limit of the server.' ) ) );
            exit;
        }
        try {
            $report = EduQode_App_Backup::import( $upload['tmp_name'], ! empty( $_POST['settings'] ) );
            set_transient( 'eduqode_backup_report_' . get_current_user_id(), $report, HOUR_IN_SECONDS );
            wp_safe_redirect( admin_url( 'tools.php?page=' . self::SLUG . '&restored=1' ) );
        } catch ( Exception $e ) {
            wp_safe_redirect( admin_url( 'tools.php?page=' . self::SLUG . '&error=' . rawurlencode( $e->getMessage() ) ) );
        }
        exit;
    }

    public static function report_lines( $r ) {
        $lines = array(
            sprintf( '%d course(s) found on this site.', $r['courses'] ),
            sprintf( 'H5P exercises: %d imported, %d already there, %d failed.', $r['h5p_imported'], $r['h5p_reused'], $r['h5p_failed'] ),
            sprintf( '%d lesson(s) and %d H5P quiz question(s) now use the restored exercises.', $r['shortcodes'], $r['questions'] ),
            sprintf( '%d offline video link(s), %d app setting(s) restored.', $r['video_links'], $r['settings'] ),
        );
        if ( $r['students_skipped'] ) {
            $lines[] = 'Students\' results of this backup were restored before, so they were not added again.';
        } else {
            $lines[] = sprintf( 'Students: %d student(s), %d result row(s) restored.', $r['students'], $r['student_rows'] );
        }
        if ( $r['courses_missing'] ) {
            $lines[] = 'Not found on this site (import them with Tutor LMS → Tools → Import first): ' . implode( ', ', $r['courses_missing'] );
        }
        if ( $r['users_missing'] ) {
            $lines[] = sprintf( '%d result(s) skipped: the student (same email) does not exist on this site.', $r['users_missing'] );
        }
        if ( $r['attempts_missing'] ) {
            $lines[] = sprintf( '%d H5P quiz result(s) skipped: the quiz attempt was not imported by Tutor LMS (choose "Keep user data" when exporting).', $r['attempts_missing'] );
        }
        return $lines;
    }

    public static function page() {
        if ( ! current_user_can( 'manage_options' ) ) {
            return;
        }
        $type    = function_exists( 'tutor' ) ? tutor()->course_post_type : 'courses';
        $courses = get_posts( array( 'post_type' => $type, 'post_status' => 'any', 'numberposts' => -1, 'orderby' => 'title', 'order' => 'ASC' ) );
        $report  = ! empty( $_GET['restored'] ) ? get_transient( 'eduqode_backup_report_' . get_current_user_id() ) : null;
        ?>
        <div class="wrap">
            <h1>EduQode Backup</h1>
            <?php if ( ! empty( $_GET['error'] ) ) : ?>
                <div class="notice notice-error"><p><?php echo esc_html( wp_unslash( $_GET['error'] ) ); ?></p></div>
            <?php endif; ?>
            <?php if ( is_array( $report ) ) : ?>
                <div class="notice notice-success"><p><strong>Restore finished.</strong></p><ul style="list-style:disc;margin-left:20px">
                    <?php foreach ( self::report_lines( $report ) as $line ) : ?><li><?php echo esc_html( $line ); ?></li><?php endforeach; ?>
                </ul></div>
            <?php endif; ?>

            <p>Moving courses to another Tutor LMS site takes two files:</p>
            <ol>
                <li><strong>Tutor LMS → Tools → Export</strong> (Tutor LMS Pro): courses, lessons, quizzes, settings, media. Turn on <em>Keep user data</em> to move students' enrollments and progress.</li>
                <li><strong>This page</strong>: what Tutor does not export — H5P exercises, students' H5P results, offline video links and the app settings.</li>
            </ol>
            <p>On the new site: first <strong>Tutor LMS → Tools → Import</strong>, then restore this backup below. Students are matched by email.</p>

            <h2>Create a backup</h2>
            <form method="post" action="<?php echo esc_url( admin_url( 'admin-post.php' ) ); ?>">
                <?php wp_nonce_field( 'eduqode_backup_export' ); ?>
                <input type="hidden" name="action" value="eduqode_backup_export">
                <p><label><input type="checkbox" name="all_courses" value="1" checked onchange="document.getElementById('eq-courses').style.display=this.checked?'none':'block'"> All courses</label></p>
                <div id="eq-courses" style="display:none;max-height:260px;overflow:auto;border:1px solid #c3c4c7;padding:8px 12px;background:#fff">
                    <?php foreach ( $courses as $c ) : ?>
                        <label style="display:block;margin:2px 0"><input type="checkbox" name="courses[]" value="<?php echo (int) $c->ID; ?>"> <?php echo esc_html( $c->post_title ); ?> <span style="color:#646970">(#<?php echo (int) $c->ID; ?>)</span></label>
                    <?php endforeach; ?>
                </div>
                <p><label><input type="checkbox" name="students" value="1" checked> Include students' results (H5P scores, "exercises finished", video positions)</label></p>
                <p><label><input type="checkbox" name="settings" value="1" checked> Include app settings</label></p>
                <?php submit_button( 'Download backup', 'primary', 'submit', false ); ?>
            </form>

            <h2 style="margin-top:32px">Restore a backup</h2>
            <form method="post" enctype="multipart/form-data" action="<?php echo esc_url( admin_url( 'admin-post.php' ) ); ?>">
                <?php wp_nonce_field( 'eduqode_backup_import' ); ?>
                <input type="hidden" name="action" value="eduqode_backup_import">
                <p><input type="file" name="backup" accept=".zip" required></p>
                <p><label><input type="checkbox" name="settings" value="1" checked> Restore app settings too</label></p>
                <p class="description">Safe to run again: exercises are not imported twice and students' results are added only once.</p>
                <?php submit_button( 'Restore backup', 'secondary', 'submit', false ); ?>
            </form>
        </div>
        <?php
    }
}

if ( defined( 'WP_CLI' ) && WP_CLI ) {
    /** Backup and restore EduQode App data (H5P exercises and results, offline video links, app settings). */
    class EduQode_App_Backup_CLI {
        /**
         * Creates a backup zip.
         *
         * [--courses=<ids>]
         * : Comma separated course ids (default: all courses).
         *
         * [--students]
         * : Include students' results.
         *
         * [--file=<path>]
         * : Where to save the zip.
         */
        public function export( $args, $assoc ) {
            $ids  = isset( $assoc['courses'] ) ? array_filter( array_map( 'absint', explode( ',', $assoc['courses'] ) ) ) : array();
            $tmp  = EduQode_App_Backup::export( $ids, isset( $assoc['students'] ) );
            $file = $assoc['file'] ?? ( 'eduqode-backup-' . gmdate( 'Y-m-d-His' ) . '.zip' );
            rename( $tmp, $file );
            WP_CLI::success( "Backup saved: $file (" . size_format( filesize( $file ) ) . ')' );
        }

        /**
         * Restores a backup zip (after Tutor LMS Pro's import).
         *
         * <file>
         * : The backup zip.
         *
         * [--no-settings]
         * : Do not restore the app settings.
         *
         * [--format=<format>]
         * : "json" prints the report as JSON.
         */
        public function import( $args, $assoc ) {
            wp_set_current_user( (int) get_users( array( 'role' => 'administrator', 'number' => 1, 'fields' => 'ID' ) )[0] );
            $report = EduQode_App_Backup::import( $args[0], ! ( isset( $assoc['settings'] ) && false === $assoc['settings'] ) );
            if ( 'json' === ( $assoc['format'] ?? '' ) ) {
                WP_CLI::line( wp_json_encode( $report ) );
                return;
            }
            foreach ( EduQode_App_Backup_Admin::report_lines( $report ) as $line ) {
                WP_CLI::line( $line );
            }
            WP_CLI::success( 'Restore finished.' );
        }
    }
}
