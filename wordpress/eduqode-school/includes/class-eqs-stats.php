<?php
/**
 * Results from Tutor LMS (nothing is copied; everything is read live):
 *   progress     Tutor's course completion percent
 *   quizzes      best finished attempt of each quiz (percent of its marks)
 *   assignments  marks given by the teacher (Tutor LMS Pro), percent of the assignment's total
 * With a date range (report card term) only quiz attempts and graded assignments of that range count.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EQS_Stats {

    private static $cache = array();

    private static function items( $course_id, $type ) {
        global $wpdb;
        $key = $type . $course_id;
        if ( ! isset( self::$cache[ $key ] ) ) {
            self::$cache[ $key ] = array_map( 'intval', $wpdb->get_col( $wpdb->prepare(
                "SELECT i.ID FROM {$wpdb->posts} i JOIN {$wpdb->posts} t ON t.ID = i.post_parent
                 WHERE i.post_type = %s AND i.post_status = 'publish' AND t.post_parent = %d ORDER BY t.menu_order, i.menu_order",
                $type, $course_id
            ) ) );
        }
        return self::$cache[ $key ];
    }

    public static function quiz_ids( $course_id ) {
        return self::items( $course_id, 'tutor_quiz' );
    }

    public static function assignment_ids( $course_id ) {
        return self::items( $course_id, 'tutor_assignments' );
    }

    /** Best finished attempt per quiz: quiz id => percent. */
    public static function quiz_scores( $course_id, $user_id, $from = '', $to = '' ) {
        global $wpdb;
        $ids = self::quiz_ids( $course_id );
        if ( ! $ids ) {
            return array();
        }
        $sql  = "SELECT quiz_id, MAX(CASE WHEN total_marks > 0 THEN earned_marks * 100.0 / total_marks ELSE 0 END) AS pct
                 FROM {$wpdb->prefix}tutor_quiz_attempts
                 WHERE user_id = %d AND quiz_id IN (" . implode( ',', $ids ) . ") AND attempt_status IN ('attempt_ended','review_required')";
        $args = array( $user_id );
        if ( $from && $to ) {
            $sql   .= ' AND attempt_ended_at >= %s AND attempt_ended_at < %s';
            $args[] = $from . ' 00:00:00';
            $args[] = gmdate( 'Y-m-d', strtotime( $to . ' +1 day' ) ) . ' 00:00:00';
        }
        $out = array();
        foreach ( $wpdb->get_results( $wpdb->prepare( $sql . ' GROUP BY quiz_id', $args ) ) as $r ) {
            $out[ (int) $r->quiz_id ] = round( (float) $r->pct, 1 );
        }
        return $out;
    }

    /** Assignments: id => [submitted, graded, percent|null]. */
    public static function assignment_scores( $course_id, $user_id, $from = '', $to = '' ) {
        global $wpdb;
        $out = array();
        foreach ( self::assignment_ids( $course_id ) as $aid ) {
            $sub = $wpdb->get_row( $wpdb->prepare(
                "SELECT comment_ID, comment_date_gmt FROM {$wpdb->comments}
                 WHERE comment_type = 'tutor_assignment' AND comment_post_ID = %d AND user_id = %d AND comment_approved = 'submitted'
                 ORDER BY comment_ID DESC LIMIT 1",
                $aid, $user_id
            ) );
            if ( ! $sub ) {
                $out[ $aid ] = array( false, false, null );
                continue;
            }
            $graded = (string) get_comment_meta( $sub->comment_ID, 'evaluate_time', true );
            if ( '' === $graded ) {
                $out[ $aid ] = array( true, false, null );
                continue;
            }
            if ( $from && $to && ( substr( $graded, 0, 10 ) < $from || substr( $graded, 0, 10 ) > $to ) ) {
                continue; // graded outside this term
            }
            $total = (float) tutor_utils()->get_assignment_option( $aid, 'total_mark', 0 );
            $mark  = (float) get_comment_meta( $sub->comment_ID, 'assignment_mark', true );
            $out[ $aid ] = array( true, true, $total > 0 ? round( $mark * 100 / $total, 1 ) : null );
        }
        return $out;
    }

    private static function avg( $values ) {
        $values = array_filter( $values, function ( $v ) {
            return null !== $v;
        } );
        return $values ? round( array_sum( $values ) / count( $values ), 1 ) : null;
    }

    /** One student in one subject (course). */
    public static function subject( $course_id, $user_id, $from = '', $to = '' ) {
        $quizzes = self::quiz_scores( $course_id, $user_id, $from, $to );
        $asg     = self::assignment_scores( $course_id, $user_id, $from, $to );
        $w       = self::weights();
        $quiz    = self::avg( $quizzes );
        $work    = self::avg( array_map( function ( $a ) {
            return $a[2];
        }, $asg ) );
        if ( null !== $quiz && null !== $work ) {
            $overall = round( ( $quiz * $w['quiz'] + $work * $w['assignment'] ) / max( 1, $w['quiz'] + $w['assignment'] ), 1 );
        } else {
            $overall = null !== $quiz ? $quiz : $work;
        }
        return array(
            'progress'        => (int) tutor_utils()->get_course_completed_percent( $course_id, $user_id ),
            'quizzes_taken'   => count( $quizzes ),
            'quizzes_total'   => count( self::quiz_ids( $course_id ) ),
            'quiz_avg'        => $quiz,
            'asg_submitted'   => count( array_filter( $asg, function ( $a ) {
                return $a[0];
            } ) ),
            'asg_graded'      => count( array_filter( $asg, function ( $a ) {
                return $a[1];
            } ) ),
            'asg_total'       => count( self::assignment_ids( $course_id ) ),
            'asg_avg'         => $work,
            'overall'         => $overall,
            'grade'           => null === $overall ? '' : self::grade( $overall ),
        );
    }

    // ------------------------------------------------------------ settings

    /** Weights of quizzes and assignments in the overall mark. */
    public static function weights() {
        $w = get_option( 'eqs_weights', array() );
        return array(
            'quiz'       => isset( $w['quiz'] ) ? max( 0, (int) $w['quiz'] ) : 50,
            'assignment' => isset( $w['assignment'] ) ? max( 0, (int) $w['assignment'] ) : 50,
        );
    }

    /** Grade scale, highest first: [min percent, grade]. */
    public static function scale() {
        $s = get_option( 'eqs_scale', '' );
        $s = is_string( $s ) && '' !== trim( $s ) ? $s : "90 A+\n80 A\n70 B\n60 C\n50 D\n0 F";
        $out = array();
        foreach ( preg_split( '/\r?\n/', $s ) as $line ) {
            if ( preg_match( '/^\s*(\d+(?:\.\d+)?)\s+(.+?)\s*$/', $line, $m ) ) {
                $out[] = array( (float) $m[1], sanitize_text_field( $m[2] ) );
            }
        }
        usort( $out, function ( $a, $b ) {
            return $b[0] <=> $a[0];
        } );
        return $out;
    }

    public static function grade( $percent ) {
        foreach ( self::scale() as $row ) {
            if ( $percent >= $row[0] ) {
                return $row[1];
            }
        }
        return '';
    }

    public static function fmt( $v ) {
        return null === $v ? '–' : rtrim( rtrim( number_format( (float) $v, 1, '.', '' ), '0' ), '.' ) . '%';
    }
}
