<?php
/**
 * Terms and report cards. A report card shows, for each subject of the student's group, the
 * results of the term (quizzes, graded assignments, overall and grade), attendance in the term
 * and the teacher's remark.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EQS_Reports {

    public static function terms() {
        global $wpdb;
        return $wpdb->get_results( 'SELECT * FROM ' . EQS_DB::t( 'terms' ) . ' ORDER BY start_date DESC, id DESC' );
    }

    public static function term( $id ) {
        global $wpdb;
        return $wpdb->get_row( $wpdb->prepare( 'SELECT * FROM ' . EQS_DB::t( 'terms' ) . ' WHERE id = %d', $id ) );
    }

    public static function add_term( $name, $start, $end, $session = '' ) {
        global $wpdb;
        if ( '' === trim( $name ) || ! EQS_Attendance::valid_date( $start ) || ! EQS_Attendance::valid_date( $end ) || $end < $start ) {
            return 0;
        }
        $wpdb->insert( EQS_DB::t( 'terms' ), array( 'name' => sanitize_text_field( $name ), 'start_date' => $start, 'end_date' => $end, 'session' => sanitize_text_field( $session ) ) );
        return (int) $wpdb->insert_id;
    }

    public static function delete_term( $id ) {
        global $wpdb;
        $wpdb->delete( EQS_DB::t( 'terms' ), array( 'id' => absint( $id ) ) );
        $wpdb->delete( EQS_DB::t( 'remarks' ), array( 'term_id' => absint( $id ) ) );
    }

    public static function remark( $term_id, $group_id, $student_id ) {
        global $wpdb;
        return (string) $wpdb->get_var( $wpdb->prepare( 'SELECT remark FROM ' . EQS_DB::t( 'remarks' ) . ' WHERE term_id = %d AND group_id = %d AND student_id = %d', $term_id, $group_id, $student_id ) );
    }

    public static function save_remark( $term_id, $group_id, $student_id, $text, $by ) {
        global $wpdb;
        $wpdb->replace( EQS_DB::t( 'remarks' ), array(
            'term_id' => (int) $term_id, 'group_id' => (int) $group_id, 'student_id' => (int) $student_id,
            'remark' => sanitize_textarea_field( $text ), 'updated_by' => (int) $by, 'updated_at' => current_time( 'mysql', true ),
        ) );
    }

    /** Everything on one report card. */
    public static function card( $student_id, $group_id, $term_id ) {
        $term = self::term( $term_id );
        $g    = EQS_Model::group( $group_id );
        if ( ! $term || ! $g ) {
            return null;
        }
        $rows   = array();
        $totals = array();
        foreach ( EQS_Model::subjects( $group_id ) as $s ) {
            $r      = EQS_Stats::subject( $s->course_id, $student_id, $term->start_date, $term->end_date );
            $rows[] = array( 'subject' => EQS_Model::subject_name( $s ), 'teacher' => $s->teacher_id ? get_the_author_meta( 'display_name', $s->teacher_id ) : '' ) + $r;
            if ( null !== $r['overall'] ) {
                $totals[] = $r['overall'];
            }
        }
        $overall = $totals ? round( array_sum( $totals ) / count( $totals ), 1 ) : null;
        $roll    = '';
        foreach ( EQS_Model::student_groups( $student_id ) as $sg ) {
            if ( (int) $sg->id === (int) $group_id ) {
                $roll = $sg->roll_no;
            }
        }
        return array(
            'student'    => get_the_author_meta( 'display_name', $student_id ),
            'roll_no'    => $roll,
            'group'      => EQS_Model::label( $g ),
            'term'       => $term,
            'rows'       => $rows,
            'overall'    => $overall,
            'grade'      => null === $overall ? '' : EQS_Stats::grade( $overall ),
            // Attendance may be taken for the section or for the whole class.
            'attendance' => EQS_Attendance::summary( $student_id, $term->start_date, $term->end_date, array( $group_id, (int) $g->parent_id ) ),
            'remark'     => self::remark( $term_id, $group_id, $student_id ),
        );
    }
}
