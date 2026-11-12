<?php
/**
 * Daily attendance per group. Status: P present, A absent, L late (counts as present), E excused.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EQS_Attendance {

    const STATUSES = array( 'P' => 'Present', 'A' => 'Absent', 'L' => 'Late', 'E' => 'Leave' );

    public static function valid_date( $d ) {
        return is_string( $d ) && preg_match( '/^\d{4}-\d{2}-\d{2}$/', $d ) && strtotime( $d ) !== false;
    }

    /** student id => status for one group and day. */
    public static function day( $group_id, $date ) {
        global $wpdb;
        $out = array();
        foreach ( $wpdb->get_results( $wpdb->prepare( 'SELECT student_id, status FROM ' . EQS_DB::t( 'attendance' ) . ' WHERE group_id = %d AND att_date = %s', $group_id, $date ) ) as $r ) {
            $out[ (int) $r->student_id ] = $r->status;
        }
        return $out;
    }

    /** Saves a day; only students of the group, only known statuses. Returns rows saved. */
    public static function save( $group_id, $date, $statuses, $by ) {
        global $wpdb;
        if ( ! self::valid_date( $date ) || $date > current_time( 'Y-m-d' ) ) {
            return 0;
        }
        $members = array_map( function ( $s ) {
            return (int) $s->user_id;
        }, EQS_Model::students( $group_id ) );
        $n = 0;
        foreach ( (array) $statuses as $sid => $st ) {
            $sid = (int) $sid;
            $st  = strtoupper( (string) $st );
            if ( ! in_array( $sid, $members, true ) || ! isset( self::STATUSES[ $st ] ) ) {
                continue;
            }
            $wpdb->replace( EQS_DB::t( 'attendance' ), array(
                'group_id' => (int) $group_id, 'att_date' => $date, 'student_id' => $sid, 'status' => $st,
                'marked_by' => (int) $by, 'updated_at' => current_time( 'mysql', true ),
            ) );
            $n++;
        }
        return $n;
    }

    /** Counts and percent present (present + late) for a student, optionally in a date range and groups. */
    public static function summary( $student_id, $from = '', $to = '', $group_id = 0 ) {
        global $wpdb;
        $sql  = 'SELECT status, COUNT(*) AS n FROM ' . EQS_DB::t( 'attendance' ) . ' WHERE student_id = %d';
        $args = array( $student_id );
        $groups = array_filter( array_map( 'intval', (array) $group_id ) );
        if ( $groups ) {
            $sql .= ' AND group_id IN (' . implode( ',', $groups ) . ')';
        }
        if ( $from && $to ) {
            $sql   .= ' AND att_date >= %s AND att_date <= %s';
            $args[] = $from;
            $args[] = $to;
        }
        $c = array( 'P' => 0, 'A' => 0, 'L' => 0, 'E' => 0 );
        foreach ( $wpdb->get_results( $wpdb->prepare( $sql . ' GROUP BY status', $args ) ) as $r ) {
            $c[ $r->status ] = (int) $r->n;
        }
        $days = array_sum( $c );
        return $c + array(
            'days'    => $days,
            'percent' => $days ? round( ( $c['P'] + $c['L'] ) * 100 / $days, 1 ) : null,
        );
    }

    /** Last days marked for a student (newest first). */
    public static function recent( $student_id, $limit = 30 ) {
        global $wpdb;
        return $wpdb->get_results( $wpdb->prepare(
            'SELECT a.att_date, a.status, a.group_id FROM ' . EQS_DB::t( 'attendance' ) . ' a WHERE a.student_id = %d ORDER BY a.att_date DESC LIMIT %d',
            $student_id, $limit
        ) );
    }

    /** Share of students marked present today in a group (null when not marked yet). */
    public static function today_percent( $group_id ) {
        $day = self::day( $group_id, current_time( 'Y-m-d' ) );
        if ( ! $day ) {
            return null;
        }
        $present = count( array_filter( $day, function ( $s ) {
            return 'P' === $s || 'L' === $s;
        } ) );
        return round( $present * 100 / count( $day ), 1 );
    }
}
