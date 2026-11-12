<?php
/**
 * Offline quizzes.
 *
 * Package: quiz settings, questions and answers (including which answers are correct, so the app can
 * show the result immediately offline; chosen by the site owner).
 *
 * Sync: an offline attempt is replayed through Tutor LMS's own functions, so the attempt, marks,
 * pass/fail and "review required" are stored exactly like an attempt taken on the website:
 *   Quiz::quiz_attempt()           creates the attempt (Tutor LMS 2.6.1+)
 *   Quiz::manage_attempt_answers() grades and saves the answers (Tutor LMS 2.6.1+)
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Quiz {

    /** Question types the app can show and grade offline (others keep the quiz online only). */
    const OFFLINE_TYPES = array(
        'true_false', 'single_choice', 'multiple_choice', 'fill_in_the_blank',
        'open_ended', 'short_answer', 'ordering', 'matching', 'image_matching', 'image_answering',
    );

    public static function tutor_supports_offline() {
        return class_exists( '\TUTOR\Quiz' )
            && method_exists( '\TUTOR\Quiz', 'quiz_attempt' )
            && method_exists( '\TUTOR\Quiz', 'manage_attempt_answers' );
    }

    private static function settings( $quiz_id ) {
        $s = tutor_utils()->get_quiz_option( $quiz_id );
        return is_array( $s ) ? $s : array();
    }

    private static function time_limit_seconds( $settings ) {
        $value = (int) ( $settings['time_limit']['time_value'] ?? 0 );
        $type  = (string) ( $settings['time_limit']['time_type'] ?? 'minutes' );
        $mult  = array( 'seconds' => 1, 'minutes' => 60, 'hours' => 3600, 'days' => 86400, 'weeks' => 604800 );
        return $value * ( $mult[ $type ] ?? 60 );
    }

    /** Attempts allowed, the way Tutor counts them (0 = unlimited). */
    public static function attempts_allowed( $quiz_id ) {
        $s       = self::settings( $quiz_id );
        $limit   = '1' === (string) ( $s['limit_attempts_allowed'] ?? '0' );
        $allowed = (int) ( $s['attempts_allowed'] ?? 0 );
        return $limit ? $allowed : 1;
    }

    public static function attempts( $quiz_id, $user_id ) {
        global $wpdb;
        return $wpdb->get_results(
            $wpdb->prepare(
                "SELECT attempt_id, attempt_status, total_marks, earned_marks, result, attempt_ended_at
                 FROM {$wpdb->prefix}tutor_quiz_attempts WHERE quiz_id = %d AND user_id = %d ORDER BY attempt_id DESC",
                $quiz_id,
                $user_id
            )
        );
    }

    private static function questions( $quiz_id ) {
        $rows = tutor_utils()->get_questions_by_quiz( $quiz_id );
        return is_array( $rows ) ? $rows : array();
    }

    /** Null when the quiz cannot be taken offline (with the reason in $reason). */
    public static function package( $quiz_id, $user_id, &$reason, &$images, &$h5p_libraries = array() ) {
        if ( ! self::tutor_supports_offline() ) {
            $reason = 'This Tutor LMS version cannot record offline quiz attempts.';
            return null;
        }
        $questions = self::questions( $quiz_id );
        if ( ! $questions ) {
            $reason = 'No questions.';
            return null;
        }
        $out   = array();
        $types = array_unique( array_map( fn( $q ) => (string) ( (object) $q )->question_type, $questions ) );
        if ( in_array( 'h5p', $types, true ) ) {
            // Tutor LMS Pro grades H5P quizzes only when every question is H5P.
            if ( count( $types ) > 1 ) {
                $reason = 'Mixes H5P and other question types (needs the website).';
                return null;
            }
            if ( ! EduQode_App_H5P::available() || ! EduQode_App_H5P::tutor_addon_active() ) {
                $reason = 'H5P quizzes need the H5P plugin and Tutor LMS Pro\'s H5P addon.';
                return null;
            }
        }
        foreach ( $questions as $q ) {
            $q = (object) $q;
            if ( 'h5p' === $q->question_type ) {
                $content = EduQode_App_H5P::content( EduQode_App_H5P::question_content_id( $q ), $h5p_libraries );
                if ( ! $content ) {
                    $reason = 'An H5P question could not be prepared for offline use.';
                    return null;
                }
                $out[] = array(
                    'id'          => (int) $q->question_id,
                    'type'        => 'h5p',
                    'title'       => wp_strip_all_tags( stripslashes( (string) $q->question_title ) ),
                    'description' => '',
                    'explanation' => wp_strip_all_tags( (string) ( $q->answer_explanation ?? '' ) ),
                    'mark'        => (float) $q->question_mark,
                    'order'       => (int) $q->question_order,
                    'randomize'   => false,
                    'answers'     => array(),
                    'h5p'         => $content,
                );
                continue;
            }
            if ( ! in_array( $q->question_type, self::OFFLINE_TYPES, true ) ) {
                $reason = 'Has question types that need the website (' . $q->question_type . ').';
                return null;
            }
            $answers = array();
            foreach ( \Tutor\Models\QuizModel::get_question_answers( $q->question_id, $q->question_type ) as $a ) {
                $img = ! empty( $a->image_url ) ? (string) $a->image_url : ( $a->image_id ? (string) wp_get_attachment_url( $a->image_id ) : '' );
                if ( $img ) {
                    $images[] = $img;
                }
                $answers[] = array(
                    'id'         => (int) $a->answer_id,
                    'title'      => wp_strip_all_tags( (string) $a->answer_title ),
                    'is_correct' => (bool) $a->is_correct,
                    'image'      => $img,
                    'match'      => (string) $a->answer_two_gap_match,
                    'order'      => (int) $a->answer_order,
                );
            }
            $description = wpautop( (string) $q->question_description );
            if ( preg_match_all( '/<img[^>]+src=["\']([^"\']+)["\']/i', $description, $m ) ) {
                foreach ( $m[1] as $src ) {
                    $images[] = html_entity_decode( $src, ENT_QUOTES, 'UTF-8' );
                }
            }
            $settings = maybe_unserialize( $q->question_settings );
            $out[]    = array(
                'id'          => (int) $q->question_id,
                'type'        => (string) $q->question_type,
                'title'       => wp_strip_all_tags( stripslashes( (string) $q->question_title ) ),
                'description' => $description,
                'explanation' => wp_strip_all_tags( (string) ( $q->answer_explanation ?? '' ) ),
                'mark'        => (float) $q->question_mark,
                'order'       => (int) $q->question_order,
                'randomize'   => is_array( $settings ) && ! empty( $settings['randomize_question'] ),
                'answers'     => $answers,
            );
        }

        $s        = self::settings( $quiz_id );
        $attempts = self::attempts( $quiz_id, $user_id );
        return array(
            'time_limit_seconds' => self::time_limit_seconds( $s ),
            'attempts_allowed'   => self::attempts_allowed( $quiz_id ),
            'attempts_used'      => count( $attempts ),
            'passing_grade'      => (float) ( $s['passing_grade'] ?? 80 ),
            'max_questions'      => (int) ( $s['max_questions_for_answer'] ?? 0 ),
            'questions_order'    => (string) ( $s['questions_order'] ?? 'rand' ),
            'reveal_answers'     => '1' === (string) ( $s['enable_answer_reveal'] ?? '0' ),
            'questions'          => $out,
            'last_attempt'       => $attempts ? self::attempt_summary( $attempts[0] ) : null,
        );
    }

    public static function attempt_summary( $a ) {
        return array(
            'attempt_id' => (int) $a->attempt_id,
            'status'     => (string) $a->attempt_status,
            'total'      => (float) $a->total_marks,
            'earned'     => (float) $a->earned_marks,
            'result'     => (string) ( $a->result ?? '' ),
        );
    }

    /**
     * Records one offline attempt through Tutor LMS.
     * @return array [status, reason, data]
     */
    public static function record_attempt( $event, $user_id ) {
        $quiz_id   = (int) ( $event['quiz_id'] ?? $event['lesson_id'] ?? 0 );
        $course_id = (int) ( $event['course_id'] ?? 0 );
        if ( ! self::tutor_supports_offline() ) {
            return array( 'rejected', 'unsupported', null );
        }
        if ( 'tutor_quiz' !== get_post_type( $quiz_id ) || (int) tutor_utils()->get_course_id_by( 'quiz', $quiz_id ) !== $course_id ) {
            return array( 'rejected', 'not_in_course', null );
        }
        $allowed = self::attempts_allowed( $quiz_id );
        if ( $allowed > 0 && count( self::attempts( $quiz_id, $user_id ) ) >= $allowed ) {
            return array( 'rejected', 'attempts_exceeded', null );
        }

        $valid_ids    = array_map( 'intval', wp_list_pluck( array_map( fn( $q ) => (object) $q, self::questions( $quiz_id ) ), 'question_id' ) );
        $question_ids = array_values( array_intersect( array_map( 'intval', (array) ( $event['question_ids'] ?? array() ) ), $valid_ids ) );
        $answers      = array();
        $h5p          = array();
        $types        = array();
        foreach ( self::questions( $quiz_id ) as $q ) {
            $types[ (int) ( (object) $q )->question_id ] = (string) ( (object) $q )->question_type;
        }
        foreach ( (array) ( $event['answers'] ?? array() ) as $qid => $value ) {
            if ( ! in_array( (int) $qid, $question_ids, true ) ) {
                continue;
            }
            if ( 'h5p' === ( $types[ (int) $qid ] ?? '' ) ) {
                // Results are stored for Tutor LMS Pro; the form field itself is empty, like on the website.
                if ( is_array( $value ) && isset( $value['h5p'] ) && is_array( $value['h5p'] ) ) {
                    $h5p[ (int) $qid ] = $value['h5p'];
                }
                $answers[ (int) $qid ] = '';
                continue;
            }
            $answers[ (int) $qid ] = $value;
        }
        if ( function_exists( 'tutor_sanitize_data' ) ) {
            $answers = tutor_sanitize_data( $answers );
        }

        $attempt_id = \TUTOR\Quiz::quiz_attempt( $course_id, $quiz_id, $user_id );
        if ( ! $attempt_id ) {
            return array( 'rejected', 'could_not_start', null );
        }
        if ( $h5p ) {
            EduQode_App_H5P::record_quiz_answers( $h5p, $quiz_id, $attempt_id, $course_id, $user_id, $event );
        }
        $attempt = tutor_utils()->get_attempt( $attempt_id );
        \TUTOR\Quiz::manage_attempt_answers(
            array(
                $attempt_id => array(
                    'quiz_question_ids' => $question_ids,
                    'quiz_question'     => $answers,
                ),
            ),
            $attempt,
            $attempt_id,
            $course_id,
            $user_id
        );

        // Keep the real times from the phone (in the site's time zone, never in the future).
        global $wpdb;
        $offset  = (int) tutor_time() - time();
        $now     = time();
        $started = min( $now, (int) floor( (int) ( $event['started_at'] ?? 0 ) / 1000 ) );
        $ended   = min( $now, (int) floor( (int) ( $event['client_time'] ?? 0 ) / 1000 ) );
        if ( $started > 0 && $ended >= $started ) {
            $wpdb->update(
                $wpdb->prefix . 'tutor_quiz_attempts',
                array(
                    'attempt_started_at' => gmdate( 'Y-m-d H:i:s', $started + $offset ),
                    'attempt_ended_at'   => gmdate( 'Y-m-d H:i:s', $ended + $offset ),
                ),
                array( 'attempt_id' => $attempt_id )
            );
        }

        $saved = tutor_utils()->get_attempt( $attempt_id );
        return array( 'applied', '', self::attempt_summary( $saved ) );
    }
}
