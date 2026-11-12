<?php
/**
 * Learning insights: how each learner (student or worker) does in each course, from the data
 * Tutor LMS already keeps (quiz attempts and answers, lesson completion).
 *
 *   attempts to pass, time per question, best/first/last score, unfinished attempts,
 *   questions answered wrong most often, last activity,
 *   and patterns (signs to look at, not judgements):
 *     strong      passed every quiz on the first attempt with 80% or more
 *     rushing     answers in a few seconds per question and fails: guessing, not reading
 *     struggling  takes long and still fails more than once: needs another way of explaining
 *     stalled     no activity for 14 days, or leaves quizzes unfinished
 *     forgetting  passed a quiz and later failed it again: needs revision
 *     improving   score went up 20 points or more between attempts
 *
 * The time per question is the time of the whole attempt divided by the answered questions
 * (Tutor LMS does not store the time of each question).
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EQS_Insights {

    const FAST_SECONDS = 8;      // per question: faster than this and failing = guessing
    const SLOW_FACTOR  = 1.5;    // slower than 1.5 × the team's middle value = taking long
    const SLOW_SECONDS = 40;     // used when there is no team to compare with
    const IDLE_DAYS    = 14;

    // ------------------------------------------------------------ worker profile

    /** Years of experience (one decimal), or null. Stored as a start date so it grows by itself. */
    public static function experience( $user_id ) {
        $since = (string) get_user_meta( $user_id, 'eqs_experience_since', true );
        if ( '' === $since || ! strtotime( $since ) ) {
            return null;
        }
        return max( 0, floor( ( time() - strtotime( $since ) ) / YEAR_IN_SECONDS * 10 ) / 10 );
    }

    public static function set_experience( $user_id, $years ) {
        $years = trim( (string) $years );
        if ( '' === $years || ! is_numeric( $years ) || (float) $years < 0 || (float) $years > 60 ) {
            return false;
        }
        update_user_meta( $user_id, 'eqs_experience_since', gmdate( 'Y-m-d', (int) ( time() - (float) $years * YEAR_IN_SECONDS ) ) );
        return true;
    }

    /** "0–1", "1–3", "3–5", "5+" years. */
    public static function band( $years ) {
        if ( null === $years ) {
            return '';
        }
        return $years < 1 ? '0–1' : ( $years < 3 ? '1–3' : ( $years < 5 ? '3–5' : '5+' ) );
    }

    // ------------------------------------------------------------ data

    private static function lesson_ids( $course_id ) {
        global $wpdb;
        return array_map( 'intval', $wpdb->get_col( $wpdb->prepare(
            "SELECT i.ID FROM {$wpdb->posts} i JOIN {$wpdb->posts} t ON t.ID = i.post_parent
             WHERE i.post_type = %s AND i.post_status = 'publish' AND t.post_parent = %d",
            function_exists( 'tutor' ) ? tutor()->lesson_post_type : 'lesson', $course_id
        ) ) );
    }

    private static function passing_grade( $quiz_id ) {
        return (float) tutor_utils()->get_quiz_option( $quiz_id, 'passing_grade', 80 );
    }

    /** Attempts of one learner in the quizzes of a course, oldest first. */
    private static function attempts( $course_id, $user_id ) {
        global $wpdb;
        $ids = EQS_Stats::quiz_ids( $course_id );
        if ( ! $ids ) {
            return array();
        }
        return $wpdb->get_results( $wpdb->prepare(
            "SELECT attempt_id, quiz_id, total_answered_questions, total_questions, total_marks, earned_marks, attempt_status, result,
                    attempt_started_at, attempt_ended_at
             FROM {$wpdb->prefix}tutor_quiz_attempts WHERE user_id = %d AND quiz_id IN (" . implode( ',', $ids ) . ')
             ORDER BY attempt_id',
            $user_id
        ) );
    }

    private static function finished( $a ) {
        return in_array( $a->attempt_status, array( 'attempt_ended', 'review_required', 'attempt_timeout' ), true );
    }

    private static function pct( $a ) {
        return (float) $a->total_marks > 0 ? round( (float) $a->earned_marks * 100 / (float) $a->total_marks, 1 ) : 0.0;
    }

    private static function passed( $a ) {
        if ( in_array( (string) $a->result, array( 'pass', 'fail' ), true ) ) {
            return 'pass' === $a->result;
        }
        return 'attempt_ended' === $a->attempt_status && self::pct( $a ) >= self::passing_grade( (int) $a->quiz_id );
    }

    // ------------------------------------------------------------ one learner, one course

    /**
     * Everything about one learner in one course.
     * $team_seconds: the team's middle time per question, to compare with (optional).
     */
    public static function learner( $course_id, $user_id, $team_seconds = null ) {
        $now      = time();
        $quizzes  = array();
        $seconds  = 0;
        $answered = 0;
        $last     = 0;
        foreach ( self::attempts( $course_id, $user_id ) as $a ) {
            $q = (int) $a->quiz_id;
            if ( ! isset( $quizzes[ $q ] ) ) {
                $quizzes[ $q ] = array( 'attempts' => 0, 'passed_at' => null, 'first' => null, 'best' => null, 'last' => null,
                    'unfinished' => 0, 'failed' => 0, 'failed_after_pass' => false, 'seconds' => 0 );
            }
            $r     = &$quizzes[ $q ];
            $start = strtotime( (string) $a->attempt_started_at );
            $end   = strtotime( (string) $a->attempt_ended_at );
            $last  = max( $last, (int) $end, (int) $start );
            if ( ! self::finished( $a ) ) {
                // Started and left (an attempt still open after a day counts as left).
                if ( $start && $now - $start > DAY_IN_SECONDS ) {
                    $r['unfinished']++;
                }
                unset( $r );
                continue;
            }
            $r['attempts']++;
            $pct = self::pct( $a );
            $ok  = self::passed( $a );
            if ( null === $r['first'] ) {
                $r['first'] = $pct;
            }
            $r['last'] = $pct;
            $r['best'] = max( (float) $r['best'], $pct );
            if ( $ok && null === $r['passed_at'] ) {
                $r['passed_at'] = $r['attempts'];
            }
            if ( ! $ok && 'review_required' !== $a->attempt_status ) {
                $r['failed']++;
                if ( null !== $r['passed_at'] ) {
                    $r['failed_after_pass'] = true;
                }
            }
            if ( $start && $end && $end > $start && $end - $start < 6 * HOUR_IN_SECONDS ) {
                $r['seconds'] += $end - $start;
                $seconds      += $end - $start;
                $answered     += max( 1, (int) $a->total_answered_questions );
            }
            unset( $r );
        }

        // Lessons completed (Tutor LMS keeps the time in user meta).
        foreach ( self::lesson_ids( $course_id ) as $lid ) {
            $last = max( $last, (int) get_user_meta( $user_id, '_tutor_completed_lesson_id_' . $lid, true ) );
        }

        $taken      = count( array_filter( $quizzes, function ( $q ) {
            return $q['attempts'] > 0;
        } ) );
        $passed     = array_filter( $quizzes, function ( $q ) {
            return null !== $q['passed_at'];
        } );
        $attempts   = array_sum( array_column( $quizzes, 'attempts' ) );
        $failed     = array_sum( array_column( $quizzes, 'failed' ) );
        $unfinished = array_sum( array_column( $quizzes, 'unfinished' ) );
        $per_q      = $answered ? round( $seconds / $answered, 1 ) : null;
        $progress   = (int) tutor_utils()->get_course_completed_percent( $course_id, $user_id );
        $best       = array_filter( array_column( $quizzes, 'best' ), function ( $v ) {
            return null !== $v;
        } );

        $out = array(
            'progress'          => $progress,
            'quizzes_total'     => count( EQS_Stats::quiz_ids( $course_id ) ),
            'quizzes_taken'     => $taken,
            'quizzes_passed'    => count( $passed ),
            'attempts'          => $attempts,
            'failed'            => $failed,
            'unfinished'        => $unfinished,
            'attempts_to_pass'  => $passed ? round( array_sum( array_column( $passed, 'passed_at' ) ) / count( $passed ), 1 ) : null,
            'best_avg'          => $best ? round( array_sum( $best ) / count( $best ), 1 ) : null,
            'minutes'           => (int) round( $seconds / 60 ),
            'seconds_per_q'     => $per_q,
            'last_activity'     => $last ?: null,
            'quizzes'           => $quizzes,
            'patterns'          => array(),
            'status'            => 'none',
        );
        $out['patterns'] = self::patterns( $out, $team_seconds );
        $out['status']   = self::status( $out );
        return $out;
    }

    private static function patterns( $r, $team_seconds ) {
        $p    = array();
        $idle = $r['last_activity'] ? (int) floor( ( time() - $r['last_activity'] ) / DAY_IN_SECONDS ) : null;
        if ( ! $r['attempts'] && ! $r['progress'] && ! $r['unfinished'] ) {
            return array( array( 'not_started', eqs__( 'Not started yet' ), '' ) );
        }
        $all_first = $r['quizzes_total'] && $r['quizzes_passed'] === $r['quizzes_total'] && $r['attempts'] === $r['quizzes_total'];
        if ( $all_first && null !== $r['best_avg'] && $r['best_avg'] >= 80 ) {
            $p[] = array( 'strong', eqs__( 'Strong' ), sprintf( eqs__( 'Passed every quiz on the first attempt (%s%% average).' ), EQS_Stats::fmt( $r['best_avg'] ) ) );
        }
        if ( null !== $r['seconds_per_q'] && $r['seconds_per_q'] < self::FAST_SECONDS && $r['failed'] > 0 ) {
            $p[] = array( 'rushing', eqs__( 'Rushing' ), sprintf( eqs__( 'About %1$s seconds per question and %2$d failed attempt(s): probably guessing instead of reading.' ), EQS_Stats::fmt( $r['seconds_per_q'] ), $r['failed'] ) );
        }
        $slow = $team_seconds ? $team_seconds * self::SLOW_FACTOR : self::SLOW_SECONDS;
        if ( null !== $r['seconds_per_q'] && $r['seconds_per_q'] >= $slow && $r['failed'] >= 2 ) {
            $p[] = array( 'struggling', eqs__( 'Trying hard but stuck' ), sprintf( eqs__( 'Takes long (about %1$s seconds per question) and still failed %2$d time(s): explain it another way, for example on the machine.' ), EQS_Stats::fmt( $r['seconds_per_q'] ), $r['failed'] ) );
        }
        if ( ( null !== $idle && $idle >= self::IDLE_DAYS && $r['progress'] < 100 ) || $r['unfinished'] >= 2 ) {
            $p[] = array( 'stalled', eqs__( 'Stopped' ), $r['unfinished'] >= 2
                ? sprintf( eqs__( 'Left %d quiz attempt(s) unfinished.' ), $r['unfinished'] )
                : sprintf( eqs__( 'No activity for %d days, course not finished.' ), $idle ) );
        }
        foreach ( $r['quizzes'] as $qid => $q ) {
            if ( $q['failed_after_pass'] ) {
                $p[] = array( 'forgetting', eqs__( 'Forgetting' ), sprintf( eqs__( 'Passed "%s" before, then failed it again: needs revision.' ), get_the_title( $qid ) ) );
                break;
            }
        }
        foreach ( $r['quizzes'] as $qid => $q ) {
            if ( $q['attempts'] >= 2 && null !== $q['first'] && $q['last'] - $q['first'] >= 20 ) {
                $p[] = array( 'improving', eqs__( 'Improving' ), sprintf( eqs__( '"%1$s": from %2$s%% to %3$s%%.' ), get_the_title( $qid ), EQS_Stats::fmt( $q['first'] ), EQS_Stats::fmt( $q['last'] ) ) );
                break;
            }
        }
        return $p;
    }

    /** good (green), help (yellow), stuck (red), none (grey: not started). */
    private static function status( $r ) {
        $codes = array_column( $r['patterns'], 0 );
        if ( in_array( 'not_started', $codes, true ) ) {
            return 'none';
        }
        $stuck = false;
        foreach ( $r['quizzes'] as $q ) {
            if ( null === $q['passed_at'] && $q['failed'] >= 3 ) {
                $stuck = true;
            }
        }
        if ( $stuck || in_array( 'stalled', $codes, true ) || in_array( 'struggling', $codes, true ) ) {
            return 'stuck';
        }
        if ( in_array( 'rushing', $codes, true ) || in_array( 'forgetting', $codes, true ) || $r['quizzes_taken'] > $r['quizzes_passed'] ) {
            return 'help';
        }
        return 'good';
    }

    public static function status_label( $s ) {
        $l = array( 'good' => eqs__( 'On track' ), 'help' => eqs__( 'Needs help' ), 'stuck' => eqs__( 'Stuck' ), 'none' => eqs__( 'Not started' ) );
        return $l[ $s ] ?? $s;
    }

    // ------------------------------------------------------------ questions

    /**
     * Questions of a course answered wrong most often by these learners:
     * [question title, quiz title, topic title, wrong, total, percent wrong].
     */
    public static function hard_questions( $course_id, array $user_ids, $limit = 5, $min_answers = 2 ) {
        global $wpdb;
        $quiz = EQS_Stats::quiz_ids( $course_id );
        $ids  = array_filter( array_map( 'intval', $user_ids ) );
        if ( ! $quiz || ! $ids ) {
            return array();
        }
        $rows = $wpdb->get_results( $wpdb->prepare(
            "SELECT a.question_id, a.quiz_id, COUNT(*) AS total, SUM(CASE WHEN a.is_correct = 1 THEN 0 ELSE 1 END) AS wrong
             FROM {$wpdb->prefix}tutor_quiz_attempt_answers a
             WHERE a.quiz_id IN (" . implode( ',', $quiz ) . ') AND a.user_id IN (' . implode( ',', $ids ) . ')
             GROUP BY a.question_id, a.quiz_id HAVING COUNT(*) >= %d',
            $min_answers
        ) );
        $out = array();
        foreach ( $rows as $r ) {
            if ( ! (int) $r->wrong ) {
                continue;
            }
            $title = (string) $wpdb->get_var( $wpdb->prepare( "SELECT question_title FROM {$wpdb->prefix}tutor_quiz_questions WHERE question_id = %d", $r->question_id ) );
            $out[] = array(
                'question' => wp_strip_all_tags( $title ),
                'quiz'     => get_the_title( (int) $r->quiz_id ),
                'topic'    => get_the_title( (int) wp_get_post_parent_id( (int) $r->quiz_id ) ),
                'wrong'    => (int) $r->wrong,
                'total'    => (int) $r->total,
                'percent'  => round( $r->wrong * 100 / $r->total ),
            );
        }
        usort( $out, function ( $a, $b ) {
            return array( $b['percent'], $b['wrong'] ) <=> array( $a['percent'], $a['wrong'] );
        } );
        return array_slice( $out, 0, $limit );
    }

    // ------------------------------------------------------------ a team

    private static function median( array $v ) {
        $v = array_values( array_filter( $v, function ( $x ) {
            return null !== $x;
        } ) );
        if ( ! $v ) {
            return null;
        }
        sort( $v );
        $n = count( $v );
        return $n % 2 ? $v[ intdiv( $n, 2 ) ] : ( $v[ $n / 2 - 1 ] + $v[ $n / 2 ] ) / 2;
    }

    /**
     * Every learner of a group in one course, compared with the group:
     * ['rows' => [user, name, id_no, years, band, learner()], 'median_seconds', 'bands' => summary by experience].
     */
    public static function team( $group_id, $course_id ) {
        $members = EQS_Model::students( $group_id );
        $first   = array();
        foreach ( $members as $m ) {
            $first[ $m->user_id ] = self::learner( $course_id, $m->user_id );
        }
        $median = self::median( array_column( $first, 'seconds_per_q' ) );
        $rows   = array();
        foreach ( $members as $m ) {
            $years  = self::experience( $m->user_id );
            $rows[] = array(
                'user'  => (int) $m->user_id,
                'name'  => $m->display_name,
                'id_no' => $m->roll_no,
                'years' => $years,
                'band'  => self::band( $years ),
                'data'  => $median ? self::learner( $course_id, $m->user_id, $median ) : $first[ $m->user_id ],
            );
        }
        $order = array( 'stuck' => 0, 'help' => 1, 'none' => 2, 'good' => 3 );
        usort( $rows, function ( $a, $b ) use ( $order ) {
            return array( $order[ $a['data']['status'] ], $a['name'] ) <=> array( $order[ $b['data']['status'] ], $b['name'] );
        } );
        return array( 'rows' => $rows, 'median_seconds' => $median, 'bands' => self::bands( $rows ) );
    }

    /** By experience: learners, average best score, average attempts to pass. */
    public static function bands( array $rows ) {
        $out = array();
        foreach ( array( '0–1', '1–3', '3–5', '5+', '' ) as $b ) {
            $in = array_filter( $rows, function ( $r ) use ( $b ) {
                return $r['band'] === $b && $r['data']['attempts'] > 0;
            } );
            if ( ! $in ) {
                continue;
            }
            $score = array_filter( array_map( function ( $r ) {
                return $r['data']['best_avg'];
            }, $in ), 'is_numeric' );
            $tries = array_filter( array_map( function ( $r ) {
                return $r['data']['attempts_to_pass'];
            }, $in ), 'is_numeric' );
            $out[] = array(
                'band'     => $b,
                'count'    => count( $in ),
                'score'    => $score ? round( array_sum( $score ) / count( $score ), 1 ) : null,
                'attempts' => $tries ? round( array_sum( $tries ) / count( $tries ), 1 ) : null,
            );
        }
        return $out;
    }

    // ------------------------------------------------------------ supervisor notes ("why")

    public static function add_note( $student_id, $author_id, $note ) {
        global $wpdb;
        $note = trim( sanitize_textarea_field( $note ) );
        if ( '' === $note ) {
            return false;
        }
        return (bool) $wpdb->insert( EQS_DB::t( 'notes' ), array(
            'student_id' => (int) $student_id,
            'author_id'  => (int) $author_id,
            'note'       => mb_substr( $note, 0, 2000 ),
            'created_at' => current_time( 'mysql', true ),
        ) );
    }

    public static function notes( $student_id ) {
        global $wpdb;
        return $wpdb->get_results( $wpdb->prepare(
            'SELECT n.*, u.display_name AS author FROM ' . EQS_DB::t( 'notes' ) . " n LEFT JOIN {$wpdb->users} u ON u.ID = n.author_id
             WHERE n.student_id = %d ORDER BY n.id DESC LIMIT 50",
            $student_id
        ) );
    }

    /** Notes are for supervisors (teachers of the learner's groups) and the principal, not for the learner or parents. */
    public static function can_use_notes( $student_id, $user_id = 0 ) {
        $user_id = $user_id ?: get_current_user_id();
        if ( EQS_Model::is_principal( $user_id ) ) {
            return true;
        }
        foreach ( EQS_Model::student_groups( $student_id ) as $g ) {
            if ( EQS_Model::can_see_group( $g->id, $user_id ) ) {
                return true;
            }
        }
        return false;
    }
}
