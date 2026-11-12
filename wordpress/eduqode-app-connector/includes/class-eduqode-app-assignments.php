<?php
/**
 * Assignments written offline in the app (Tutor LMS Pro "Assignments" addon).
 *
 * POST /assignments/{id}/submit (multipart: answer, client_id, started_at,
 * attached_assignment_files[]) submits the work exactly the way the website does:
 *   1. a "submitting" record when the student started (Tutor's "Start assignment"),
 *   2. Tutor LMS Pro's own update_assignment_submit(): answer, uploaded files, "submitted",
 *      and its hooks (e-mails to the instructor, etc.).
 * The same client_id is never submitted twice, so a retry after a lost connection is safe.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Assignments {

    const CLIENT_META = '_eduqode_client_id';
    const TYPE        = 'tutor_assignment';

    public static function available() {
        return class_exists( '\TUTOR_ASSIGNMENTS\Assignments' ) && method_exists( '\TUTOR_ASSIGNMENTS\Assignments', 'update_assignment_submit' );
    }

    /** Limits the app shows before the student starts writing. */
    public static function options( $assignment_id ) {
        $u = tutor_utils();
        return array(
            'retry'          => (bool) (int) $u->get_assignment_option( $assignment_id, 'is_retry_allowed', 1 ),
            'attempts'       => (int) $u->get_assignment_option( $assignment_id, 'attempts_allowed', 5 ),
            'max_files'      => (int) $u->get_assignment_option( $assignment_id, 'upload_files_limit', 0 ),
            'max_file_mb'    => (int) $u->get_assignment_option( $assignment_id, 'upload_file_size_limit', 2 ),
            'offline_submit' => self::available(),
        );
    }

    private static function attempts( $assignment_id, $user_id ) {
        global $wpdb;
        return (int) $wpdb->get_var( $wpdb->prepare(
            "SELECT COUNT(comment_ID) FROM {$wpdb->comments} WHERE comment_post_ID = %d AND user_id = %d AND comment_type = %s",
            $assignment_id, $user_id, self::TYPE
        ) );
    }

    private static function by_client_id( $client_id, $user_id ) {
        global $wpdb;
        return (int) $wpdb->get_var( $wpdb->prepare(
            "SELECT c.comment_ID FROM {$wpdb->comments} c JOIN {$wpdb->commentmeta} m ON m.comment_id = c.comment_ID
             WHERE m.meta_key = %s AND m.meta_value = %s AND c.user_id = %d LIMIT 1",
            self::CLIENT_META, $client_id, $user_id
        ) );
    }

    private static function error( $code, $message, $status = 400 ) {
        return new WP_Error( 'eduqode_' . $code, $message, array( 'status' => $status, 'reason' => $code ) );
    }

    /** REST handler. */
    public static function submit( WP_REST_Request $request ) {
        global $wpdb;
        $user_id       = get_current_user_id();
        $assignment_id = (int) $request['id'];
        $client_id     = preg_replace( '/[^A-Za-z0-9-]/', '', (string) $request->get_param( 'client_id' ) );
        $answer        = wp_kses_post( (string) $request->get_param( 'answer' ) );

        if ( ! self::available() ) {
            return self::error( 'unsupported', eqa__( 'Assignments need Tutor LMS Pro\'s Assignments addon on the website.' ), 503 );
        }
        if ( 'tutor_assignments' !== get_post_type( $assignment_id ) || 'publish' !== get_post_status( $assignment_id ) ) {
            return self::error( 'not_found', eqa__( 'Assignment not found.' ), 404 );
        }
        $course_id = (int) tutor_utils()->get_course_id_by( 'assignment', $assignment_id );
        if ( ! EduQode_App_Courses::can_access( $course_id, $user_id ) ) {
            return self::error( 'no_access', eqa__( 'You are not enrolled in this course.' ), 403 );
        }
        if ( strlen( $client_id ) < 8 ) {
            return self::error( 'bad_request', 'Missing client_id.' );
        }
        $existing = self::by_client_id( $client_id, $user_id );
        if ( $existing && 'submitted' === get_comment( $existing )->comment_approved ) {
            return array( 'status' => 'duplicate', 'submission_id' => $existing, 'submitted' => true );
        }
        // A retry of a submission that did not finish continues the same record.
        if ( in_array( trim( $answer ), array( '', '<p>&nbsp;</p>', '<p></p>' ), true ) ) {
            return self::error( 'empty', eqa__( 'The answer is empty.' ) );
        }

        $opt        = self::options( $assignment_id );
        $submitting = $existing ?: (int) tutor_utils()->is_assignment_submitting( $assignment_id, $user_id );
        if ( ! $submitting ) {
            // Same limits as Tutor LMS Pro's "Start assignment".
            $attempts = self::attempts( $assignment_id, $user_id );
            if ( $opt['retry'] && $attempts > $opt['attempts'] ) {
                return self::error( 'attempts_exceeded', eqa__( 'No submissions left for this assignment.' ), 409 );
            }
            if ( ! $opt['retry'] && $attempts ) {
                return self::error( 'already_submitted', eqa__( 'This assignment was already submitted.' ), 409 );
            }
        }

        // Files: the same limits Tutor LMS Pro checks (it stops the request when a file is too big).
        $files = $_FILES['attached_assignment_files'] ?? null; // phpcs:ignore
        if ( $files && is_array( $files['name'] ) ) {
            $count = count( array_filter( $files['name'] ) );
            if ( $opt['max_files'] <= 0 && $count ) {
                return self::error( 'files_not_allowed', eqa__( 'This assignment does not take files.' ) );
            }
            if ( $count > $opt['max_files'] ) {
                return self::error( 'too_many_files', sprintf( eqa__( 'At most %d file(s).' ), $opt['max_files'] ) );
            }
            foreach ( $files['size'] as $i => $size ) {
                if ( round( $size / ( 1024 * 1024 ) ) > $opt['max_file_mb'] || UPLOAD_ERR_OK !== (int) $files['error'][ $i ] ) {
                    return self::error( 'file_too_big', sprintf( eqa__( 'Each file can be at most %d MB.' ), $opt['max_file_mb'] ) );
                }
            }
        }

        if ( ! $submitting ) {
            // "Start assignment", at the time the student started on the phone (never in the future).
            $started = (int) floor( (int) $request->get_param( 'started_at' ) / 1000 );
            $started = ( $started > 0 && $started <= time() ) ? $started : time();
            $user    = get_userdata( $user_id );
            do_action( 'tutor_before_assignment_submit_start' );
            $wpdb->insert( $wpdb->comments, apply_filters( 'tutor_assignment_start_submitting_data', array(
                'comment_post_ID'  => $assignment_id,
                'comment_author'   => $user->user_login,
                'comment_date'     => wp_date( 'Y-m-d H:i:s', $started ),
                'comment_date_gmt' => gmdate( 'Y-m-d H:i:s', $started ),
                'comment_approved' => 'submitting',
                'comment_agent'    => 'TutorLMSPlugin',
                'comment_type'     => self::TYPE,
                'comment_parent'   => $course_id,
                'user_id'          => $user_id,
            ) ) );
            $submitting = (int) $wpdb->insert_id;
            do_action( 'tutor_after_assignment_submit_start', $submitting );
        }
        if ( ! $submitting ) {
            return self::error( 'could_not_start', eqa__( 'Could not start the assignment.' ), 500 );
        }
        if ( \TUTOR_ASSIGNMENTS\Assignments::is_assignment_expired( $assignment_id, $user_id, $course_id ) ) {
            return self::error( 'expired', eqa__( 'The time for this assignment is over.' ), 409 );
        }
        // Mark it before submitting: if the connection drops now, a retry is not submitted twice.
        update_comment_meta( $submitting, self::CLIENT_META, $client_id );

        $store                       = new stdClass();
        $store->update_id            = 0;
        $store->assignment_id        = $assignment_id;
        $store->assignment_answer    = $answer;
        $store->allowed_upload_files = $opt['max_files'];
        $store->assignment_submit_id = $submitting;
        $store->course_id            = $course_id;
        $store->student_id           = $user_id;

        // Tutor LMS Pro's own submit (its constructor is not run again so its hooks are not added twice).
        $tutor = ( new ReflectionClass( '\TUTOR_ASSIGNMENTS\Assignments' ) )->newInstanceWithoutConstructor();
        ob_start();
        $tutor->update_assignment_submit( $store );
        ob_end_clean();

        clean_comment_cache( $submitting );
        $row = get_comment( $submitting );
        return array(
            'status'        => 'submitted' === ( $row->comment_approved ?? '' ) ? 'applied' : 'failed',
            'submission_id' => $submitting,
            'submitted'     => 'submitted' === ( $row->comment_approved ?? '' ),
            'files'         => count( (array) json_decode( (string) get_comment_meta( $submitting, 'uploaded_attachments', true ) ) ),
        );
    }
}
