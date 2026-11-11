<?php
/**
 * Offline progress sync.
 *
 * The app sends a list of events recorded while offline. Each event has a unique ID chosen
 * by the app, so sending the same event twice is harmless ("duplicate"). Rules that keep
 * newer progress safe, including when one student uses several phones:
 *  - lesson_complete: completion is never undone; an already-completed lesson is left as it is.
 *  - video_position:  the newest position (by the time it was recorded on the phone) wins;
 *                     Tutor's "best watched time" only ever goes up.
 *  - lesson_access:   the newest "last opened lesson" wins.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Sync {

    const SEEN_META      = '_eduqode_app_events';
    const POSITIONS_META = '_eduqode_app_positions';
    const LAST_META      = '_eduqode_app_last_lesson';
    const MAX_SEEN       = 2000;
    const MAX_EVENTS     = 500;

    private $user_id;

    public function __construct( $user_id ) {
        $this->user_id = (int) $user_id;
    }

    private function meta( $key ) {
        $value = get_user_meta( $this->user_id, $key, true );
        return is_array( $value ) ? $value : array();
    }

    public function position( $lesson_id ) {
        $all = $this->meta( self::POSITIONS_META );
        if ( isset( $all[ $lesson_id ] ) ) {
            return array(
                'position_ms' => (int) $all[ $lesson_id ]['p'],
                'duration_ms' => (int) $all[ $lesson_id ]['d'],
                'updated_at'  => (int) $all[ $lesson_id ]['t'],
            );
        }
        return null;
    }

    public function last_lesson( $course_id ) {
        $all = $this->meta( self::LAST_META );
        if ( isset( $all[ $course_id ] ) ) {
            return array(
                'lesson_id'  => (int) $all[ $course_id ]['l'],
                'updated_at' => (int) $all[ $course_id ]['t'],
            );
        }
        return null;
    }

    /** Current server progress for one course. */
    public function progress( $course_id ) {
        $completed = array();
        $positions = array();
        foreach ( EduQode_App_Courses::structure( $course_id ) as $topic ) {
            foreach ( $topic['items'] as $item ) {
                if ( $item->post_type !== tutor()->lesson_post_type ) {
                    continue;
                }
                $done = tutor_utils()->is_completed_lesson( $item->ID, $this->user_id );
                if ( $done ) {
                    $completed[ (string) $item->ID ] = is_numeric( $done ) ? (int) $done * 1000 : 1;
                }
                $pos = $this->position( $item->ID );
                if ( $pos ) {
                    $positions[ (string) $item->ID ] = $pos;
                }
            }
        }
        return array(
            'course_id'   => (int) $course_id,
            'percent'     => (int) tutor_utils()->get_course_completed_percent( $course_id, $this->user_id ),
            'completed'   => (object) $completed,
            'positions'   => (object) $positions,
            'last_lesson' => $this->last_lesson( $course_id ),
            'server_time' => time() * 1000,
        );
    }

    /**
     * @param array $events list of events from the app.
     * @return array results per event, plus progress for every course mentioned.
     */
    public function apply( $events ) {
        $events = array_slice( array_values( (array) $events ), 0, self::MAX_EVENTS );
        // Apply in the order things happened on the phone.
        usort(
            $events,
            function ( $a, $b ) {
                return (int) ( isset( $a['client_time'] ) ? $a['client_time'] : 0 ) <=> (int) ( isset( $b['client_time'] ) ? $b['client_time'] : 0 );
            }
        );

        $seen    = $this->meta( self::SEEN_META );
        $results = array();
        $courses = array();

        foreach ( $events as $event ) {
            $id = isset( $event['id'] ) ? preg_replace( '/[^A-Za-z0-9-]/', '', (string) $event['id'] ) : '';
            if ( ! $id ) {
                continue;
            }
            if ( isset( $seen[ $id ] ) ) {
                $results[] = array( 'id' => $id, 'status' => 'duplicate' );
                continue;
            }

            list( $status, $reason ) = $this->apply_one( $event );
            $result = array( 'id' => $id, 'status' => $status );
            if ( $reason ) {
                $result['reason'] = $reason;
            }
            $results[] = $result;

            $course_id = (int) ( isset( $event['course_id'] ) ? $event['course_id'] : 0 );
            if ( $course_id && 'no_access' !== $reason ) {
                $courses[ $course_id ] = true;
            }
            // Remember the event so a retry is not applied twice. Rejected events are also
            // remembered: retrying them would give the same answer.
            $seen[ $id ] = time();
        }

        if ( count( $seen ) > self::MAX_SEEN ) {
            arsort( $seen );
            $seen = array_slice( $seen, 0, self::MAX_SEEN, true );
        }
        update_user_meta( $this->user_id, self::SEEN_META, $seen );

        $progress = array();
        foreach ( array_keys( $courses ) as $course_id ) {
            if ( EduQode_App_Courses::can_access( $course_id, $this->user_id ) ) {
                $progress[] = $this->progress( $course_id );
            }
        }

        return array(
            'results'  => $results,
            'progress' => $progress,
        );
    }

    /** Removes Tutor's "redirect to next lesson" callbacks; returns them for restore. */
    private function pause_redirect_callbacks() {
        global $wp_filter;
        $paused = array();
        if ( empty( $wp_filter['tutor_lesson_completed_after'] ) ) {
            return $paused;
        }
        foreach ( $wp_filter['tutor_lesson_completed_after']->callbacks as $priority => $callbacks ) {
            foreach ( $callbacks as $cb ) {
                $fn = $cb['function'];
                if ( is_array( $fn ) && is_object( $fn[0] ) && 'tutor_lesson_completed_after' === $fn[1]
                    && 0 === strpos( get_class( $fn[0] ), 'TUTOR' ) ) {
                    remove_action( 'tutor_lesson_completed_after', $fn, $priority );
                    $paused[] = array( $fn, $priority, $cb['accepted_args'] );
                }
            }
        }
        return $paused;
    }

    private function restore_callbacks( $paused ) {
        foreach ( $paused as $p ) {
            add_action( 'tutor_lesson_completed_after', $p[0], $p[1], $p[2] );
        }
    }

    /** @return array [status, reason] */
    private function apply_one( $event ) {
        $type        = isset( $event['type'] ) ? (string) $event['type'] : '';
        $course_id   = (int) ( isset( $event['course_id'] ) ? $event['course_id'] : 0 );
        $lesson_id   = (int) ( isset( $event['lesson_id'] ) ? $event['lesson_id'] : 0 );
        $client_time = (int) ( isset( $event['client_time'] ) ? $event['client_time'] : 0 );
        // A phone clock far in the future must not lock in a value forever.
        $client_time = min( $client_time, ( time() + DAY_IN_SECONDS ) * 1000 );

        if ( ! in_array( $type, array( 'lesson_complete', 'video_position', 'lesson_access' ), true ) ) {
            return array( 'rejected', 'unknown_type' );
        }
        if ( ! EduQode_App_Courses::can_access( $course_id, $this->user_id ) ) {
            return array( 'rejected', 'no_access' );
        }
        if ( ! EduQode_App_Courses::lesson_in_course( $lesson_id, $course_id ) ) {
            return array( 'rejected', 'not_in_course' );
        }
        $is_lesson = get_post_type( $lesson_id ) === tutor()->lesson_post_type;

        if ( 'lesson_complete' === $type ) {
            if ( ! $is_lesson ) {
                return array( 'rejected', 'not_a_lesson' );
            }
            if ( tutor_utils()->is_completed_lesson( $lesson_id, $this->user_id ) ) {
                return array( 'already', '' );
            }
            // Same checks and hooks Tutor LMS runs when a lesson is completed on the website.
            if ( ! apply_filters( 'tutor_validate_lesson_complete', true, $this->user_id, $lesson_id ) ) {
                return array( 'rejected', 'validation' );
            }
            // Tutor's own website handler redirects the browser to the next lesson and stops
            // the request (Lesson::tutor_lesson_completed_after). That would cut off the app's
            // sync response, so that one callback is paused while the hooks run.
            $paused = $this->pause_redirect_callbacks();
            add_filter( 'wp_redirect', '__return_false', 9999 );
            do_action( 'tutor_lesson_completed_before', $lesson_id );
            tutor_utils()->mark_lesson_complete( $lesson_id, $this->user_id );
            do_action( 'tutor_lesson_completed_email_after', $lesson_id, $this->user_id );
            do_action( 'tutor_lesson_completed_after', $lesson_id, $this->user_id );
            remove_filter( 'wp_redirect', '__return_false', 9999 );
            $this->restore_callbacks( $paused );
            return array( 'applied', '' );
        }

        if ( 'video_position' === $type ) {
            if ( ! $is_lesson ) {
                return array( 'rejected', 'not_a_lesson' );
            }
            $pos = max( 0, (int) ( isset( $event['position_ms'] ) ? $event['position_ms'] : 0 ) );
            $dur = max( 0, (int) ( isset( $event['duration_ms'] ) ? $event['duration_ms'] : 0 ) );

            // Tutor's own "best watched time" (seconds) only ever increases.
            $best = (float) tutor_utils()->get_lesson_reading_info( $lesson_id, $this->user_id, 'video_best_watched_time' );
            if ( $pos / 1000 > $best ) {
                tutor_utils()->update_lesson_reading_info( $lesson_id, $this->user_id, 'video_best_watched_time', $pos / 1000 );
            }

            $all = $this->meta( self::POSITIONS_META );
            if ( isset( $all[ $lesson_id ] ) && (int) $all[ $lesson_id ]['t'] >= $client_time ) {
                return array( 'stale', 'newer_on_server' );
            }
            $all[ $lesson_id ] = array( 'p' => $pos, 'd' => $dur, 't' => $client_time );
            update_user_meta( $this->user_id, self::POSITIONS_META, $all );
            return array( 'applied', '' );
        }

        // lesson_access
        $all = $this->meta( self::LAST_META );
        if ( isset( $all[ $course_id ] ) && (int) $all[ $course_id ]['t'] >= $client_time ) {
            return array( 'stale', 'newer_on_server' );
        }
        $all[ $course_id ] = array( 'l' => $lesson_id, 't' => $client_time );
        update_user_meta( $this->user_id, self::LAST_META, $all );
        return array( 'applied', '' );
    }
}
