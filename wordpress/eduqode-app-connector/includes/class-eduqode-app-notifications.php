<?php
/**
 * What is new for a student since a time, for phone notifications (the app asks about every hour):
 *   new_content        a lesson, quiz or assignment published in an enrolled course
 *   announcement       a Tutor LMS announcement in an enrolled course
 *   assignment_graded  the teacher graded the student's assignment (Tutor LMS Pro)
 *   quiz_reviewed      the teacher reviewed a quiz attempt
 *   deadline           an assignment the student has not submitted closes within 24 hours
 * GET /notifications?since=<ms>
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Notifications {

    const MAX = 30;

    private static function item( $id, $type, $title, $body, $course_id, $url, $time ) {
        return array(
            'id'        => $id,
            'type'      => $type,
            'title'     => html_entity_decode( wp_strip_all_tags( $title ), ENT_QUOTES, 'UTF-8' ),
            'body'      => html_entity_decode( wp_trim_words( wp_strip_all_tags( $body ), 30 ), ENT_QUOTES, 'UTF-8' ),
            'course_id' => (int) $course_id,
            'url'       => (string) $url,
            'time'      => (int) $time * 1000,
        );
    }

    public static function since( $user_id, $since_ms, $switch_language = true ) {
        // In the student's own language (Urdu or English). The app's requests are switched already.
        $switched = $switch_language && eqa_use_user_language( $user_id );
        $out      = self::collect( $user_id, $since_ms );
        if ( $switched ) {
            eqa_restore_language();
        }
        return $out;
    }

    private static function collect( $user_id, $since_ms ) {
        global $wpdb;
        $now     = time();
        // At most 14 days back, so a phone that was off for months does not get a flood.
        $since   = max( (int) floor( (int) $since_ms / 1000 ), $now - 14 * DAY_IN_SECONDS );
        $courses = array_values( array_filter(
            EduQode_App_Courses::enrolled_course_ids( $user_id ),
            function ( $c ) {
                return 'publish' === get_post_status( $c );
            }
        ) );
        $out = array();
        if ( ! $courses ) {
            return $out;
        }
        $in       = implode( ',', array_map( 'intval', $courses ) );
        $since_dt = gmdate( 'Y-m-d H:i:s', $since );
        $title    = function ( $id ) {
            return get_the_title( $id );
        };

        // New lessons, quizzes and assignments (published after "since", inside an enrolled course).
        $types = array( tutor()->lesson_post_type, 'tutor_quiz', 'tutor_assignments' );
        $rows  = $wpdb->get_results( $wpdb->prepare(
            "SELECT p.ID, p.post_type, p.post_title, p.post_date_gmt, t.post_parent AS course_id
             FROM {$wpdb->posts} p JOIN {$wpdb->posts} t ON t.ID = p.post_parent
             WHERE p.post_status = 'publish' AND p.post_type IN ('" . implode( "','", array_map( 'esc_sql', $types ) ) . "')
               AND t.post_parent IN ($in) AND p.post_date_gmt > %s
             ORDER BY p.post_date_gmt DESC LIMIT 20",
            $since_dt
        ) );
        foreach ( $rows as $r ) {
            if ( EduQode_App_Courses::is_drip_locked( (int) $r->ID ) ) {
                continue;
            }
            $fmt   = 'tutor_quiz' === $r->post_type ? eqa__( 'New quiz: %s' ) : ( 'tutor_assignments' === $r->post_type ? eqa__( 'New assignment: %s' ) : eqa__( 'New lesson: %s' ) );
            $out[] = self::item( 'new-' . $r->ID, 'new_content', sprintf( $fmt, $r->post_title ), $title( $r->course_id ),
                $r->course_id, get_permalink( $r->ID ), strtotime( $r->post_date_gmt . ' UTC' ) );
        }

        // Announcements.
        $rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT ID, post_title, post_content, post_parent, post_date_gmt FROM {$wpdb->posts}
             WHERE post_type = 'tutor_announcements' AND post_status = 'publish' AND post_parent IN ($in) AND post_date_gmt > %s
             ORDER BY post_date_gmt DESC LIMIT 10",
            $since_dt
        ) );
        foreach ( $rows as $r ) {
            $out[] = self::item( 'ann-' . $r->ID, 'announcement', $r->post_title, $r->post_content, $r->post_parent,
                get_permalink( $r->post_parent ), strtotime( $r->post_date_gmt . ' UTC' ) );
        }

        // Graded assignments (Tutor LMS Pro saves "evaluate_time" when the teacher grades).
        $rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT c.comment_ID, c.comment_post_ID, c.comment_parent, m.meta_value AS evaluated
             FROM {$wpdb->comments} c JOIN {$wpdb->commentmeta} m ON m.comment_id = c.comment_ID AND m.meta_key = 'evaluate_time'
             WHERE c.comment_type = 'tutor_assignment' AND c.user_id = %d LIMIT 50",
            $user_id
        ) );
        foreach ( $rows as $r ) {
            $t = strtotime( (string) $r->evaluated . ' UTC' );   // Tutor LMS Pro stores it in GMT
            if ( ! $t || $t <= $since ) {
                continue;
            }
            $mark  = get_comment_meta( $r->comment_ID, 'assignment_mark', true );
            $total = tutor_utils()->get_assignment_option( $r->comment_post_ID, 'total_mark', 0 );
            $out[] = self::item( 'graded-' . $r->comment_ID . '-' . $t, 'assignment_graded', sprintf( eqa__( 'Assignment graded: %s' ), $title( $r->comment_post_ID ) ),
                '' !== $mark ? sprintf( eqa__( 'You got %s out of %s.' ), $mark, $total ) : eqa__( 'Your teacher checked your work.' ),
                $r->comment_parent, get_permalink( $r->comment_post_ID ), $t );
        }

        // Quiz attempts reviewed by the teacher.
        $rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT attempt_id, quiz_id, course_id, earned_marks, total_marks, manually_reviewed_at
             FROM {$wpdb->prefix}tutor_quiz_attempts WHERE user_id = %d AND manually_reviewed_at > %s LIMIT 20",
            $user_id, gmdate( 'Y-m-d H:i:s', $since + ( (int) tutor_time() - $now ) )
        ) );
        foreach ( (array) $rows as $r ) {
            $out[] = self::item( 'review-' . $r->attempt_id . '-' . strtotime( $r->manually_reviewed_at ), 'quiz_reviewed', sprintf( eqa__( 'Quiz checked: %s' ), $title( $r->quiz_id ) ),
                sprintf( eqa__( 'Your result: %s out of %s.' ), 0 + $r->earned_marks, 0 + $r->total_marks ), $r->course_id, get_permalink( $r->quiz_id ),
                strtotime( $r->manually_reviewed_at ) - ( (int) tutor_time() - $now ) );
        }

        // Assignment deadlines in the next 24 hours (only for assignments not submitted yet).
        if ( class_exists( '\TUTOR_ASSIGNMENTS\Assignments' ) ) {
            $rows = $wpdb->get_results(
                "SELECT p.ID, t.post_parent AS course_id FROM {$wpdb->posts} p JOIN {$wpdb->posts} t ON t.ID = p.post_parent
                 WHERE p.post_type = 'tutor_assignments' AND p.post_status = 'publish' AND t.post_parent IN ($in) LIMIT 200"
            );
            foreach ( $rows as $r ) {
                if ( tutor_utils()->is_assignment_submitted( $r->ID, $user_id ) ) {
                    continue;
                }
                $deadline = self::deadline( (int) $r->ID, $user_id, (int) $r->course_id );
                if ( $deadline && $deadline > $now && $deadline - $now <= DAY_IN_SECONDS ) {
                    $hours = max( 1, (int) round( ( $deadline - $now ) / HOUR_IN_SECONDS ) );
                    $out[] = self::item( 'deadline-' . $r->ID . '-' . $deadline, 'deadline', sprintf( eqa__( 'Due soon: %s' ), $title( $r->ID ) ),
                        sprintf( eqa__( 'Submit within %d hour(s).' ), $hours ), $r->course_id, get_permalink( $r->ID ), $now );
                }
            }
        }

        usort( $out, function ( $a, $b ) {
            return $b['time'] <=> $a['time'];
        } );
        return array_slice( $out, 0, self::MAX );
    }

    /** Same deadline as Tutor LMS Pro (enrollment or assignment date, or start date, plus the time limit). */
    public static function deadline( $assignment_id, $user_id, $course_id ) {
        $d     = tutor_utils()->get_assignment_option( $assignment_id, 'time_duration', array() );
        $value = (int) ( is_array( $d ) ? ( $d['value'] ?? 0 ) : 0 );
        $unit  = is_array( $d ) ? (string) ( $d['time'] ?? '' ) : '';
        $sec   = array( 'hours' => HOUR_IN_SECONDS, 'days' => DAY_IN_SECONDS, 'weeks' => WEEK_IN_SECONDS );
        if ( ! $value || ! isset( $sec[ $unit ] ) ) {
            return 0;
        }
        $created  = strtotime( get_post_field( 'post_date_gmt', $assignment_id ) . ' UTC' );
        $enrolled = tutor_utils()->is_enrolled( $course_id, $user_id );
        $base     = $enrolled ? (int) apply_filters( 'tutor_content_drip_assignment_deadline', strtotime( $enrolled->post_date_gmt . ' UTC' ), $course_id, $assignment_id ) : $created;
        $base     = max( $base, $created );
        if ( tutor_utils()->get_assignment_option( $assignment_id, 'deadline_from_start' ) ) {
            $started = tutor_utils()->get_single_comment_user_post_id( $assignment_id, $user_id );
            if ( ! $started ) {
                return 0;
            }
            $base = strtotime( $started->comment_date_gmt . ' UTC' );
        }
        return $base + $value * $sec[ $unit ];
    }

    public static function rest( WP_REST_Request $request ) {
        if ( ! eduqode_app_tutor_ready() ) {
            return new WP_Error( 'eduqode_no_tutor', eqa__( 'Tutor LMS is not active.' ), array( 'status' => 503 ) );
        }
        return array(
            'server_time'   => time() * 1000,
            'notifications' => self::since( get_current_user_id(), (int) $request->get_param( 'since' ), false ),
        );
    }
}
