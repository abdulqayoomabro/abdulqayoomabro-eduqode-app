<?php
/**
 * The School page ([eduqode_school]): one page for everyone, showing what the logged-in user may see.
 *   ?view=group&id=G[&subject=S]          results of a class/section/batch (teacher, principal)
 *   ?view=attendance&group=G&date=D       take attendance
 *   ?view=student&id=U                    one student (parent, teacher, principal, the student)
 *   ?view=remarks&group=G&term=T          teacher's remarks for report cards
 *   ?view=report&student=U&group=G&term=T printable report card
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EQS_Front {

    public static function init() {
        add_shortcode( 'eduqode_school', array( __CLASS__, 'render' ) );
        add_action( 'template_redirect', array( __CLASS__, 'handle_post' ) );
        add_action( 'wp_enqueue_scripts', array( __CLASS__, 'assets' ) );
        add_filter( 'login_redirect', array( __CLASS__, 'parent_login_redirect' ), 20, 3 );
    }

    public static function page_url( $args = array() ) {
        $base = get_permalink( (int) get_option( 'eqs_page_id' ) ) ?: home_url( '/school/' );
        return add_query_arg( array_map( 'rawurlencode', $args ), $base );
    }

    public static function assets() {
        if ( is_singular() && has_shortcode( (string) get_post_field( 'post_content', get_queried_object_id() ), 'eduqode_school' ) ) {
            wp_enqueue_style( 'eqs', EQS_URL . 'assets/school.css', array(), EQS_VERSION );
        }
    }

    /** Parents (who do not use the Tutor dashboard) land on the School page after login. */
    public static function parent_login_redirect( $to, $requested, $user ) {
        if ( $user instanceof WP_User && in_array( 'eqs_parent', (array) $user->roles, true ) && ! $user->has_cap( 'edit_posts' )
            && ( '' === $requested || admin_url() === $requested || false !== strpos( (string) $requested, 'wp-admin' ) ) ) {
            return self::page_url();
        }
        return $to;
    }

    // ------------------------------------------------------------ saving

    public static function handle_post() {
        if ( 'POST' !== ( $_SERVER['REQUEST_METHOD'] ?? '' ) || empty( $_POST['eqs_action'] ) || ! is_user_logged_in() ) {
            return;
        }
        $action = sanitize_key( wp_unslash( $_POST['eqs_action'] ) );
        $me     = get_current_user_id();
        if ( 'attendance' === $action ) {
            $group = absint( $_POST['group'] ?? 0 );
            $date  = sanitize_text_field( wp_unslash( $_POST['date'] ?? '' ) );
            check_admin_referer( 'eqs_attendance_' . $group );
            if ( ! EQS_Model::can_mark_attendance( $group, $me ) ) {
                wp_die( eqs__( 'You cannot take attendance for this group.' ), 403 );
            }
            $statuses = array_map( 'sanitize_text_field', wp_unslash( (array) ( $_POST['status'] ?? array() ) ) );
            $n        = EQS_Attendance::save( $group, $date, $statuses, $me );
            wp_safe_redirect( self::page_url( array( 'view' => 'attendance', 'group' => $group, 'date' => $date, 'saved' => $n ) ) );
            exit;
        }
        if ( 'remarks' === $action ) {
            $group = absint( $_POST['group'] ?? 0 );
            $term  = absint( $_POST['term'] ?? 0 );
            check_admin_referer( 'eqs_remarks_' . $group . '_' . $term );
            if ( ! EQS_Model::can_see_group( $group, $me ) || ! EQS_Reports::term( $term ) ) {
                wp_die( eqs__( 'You cannot write remarks for this group.' ), 403 );
            }
            $members = array_map( function ( $s ) {
                return (int) $s->user_id;
            }, EQS_Model::students( $group ) );
            $n = 0;
            foreach ( wp_unslash( (array) ( $_POST['remark'] ?? array() ) ) as $sid => $text ) {
                if ( in_array( (int) $sid, $members, true ) ) {
                    EQS_Reports::save_remark( $term, $group, (int) $sid, (string) $text, $me );
                    $n++;
                }
            }
            wp_safe_redirect( self::page_url( array( 'view' => 'remarks', 'group' => $group, 'term' => $term, 'saved' => $n ) ) );
            exit;
        }
        if ( 'note' === $action ) {
            $student = absint( $_POST['student'] ?? 0 );
            check_admin_referer( 'eqs_note_' . $student );
            if ( ! EQS_Insights::can_use_notes( $student, $me ) ) {
                wp_die( eqs__( 'You cannot write notes for this person.' ), 403 );
            }
            EQS_Insights::add_note( $student, $me, (string) wp_unslash( $_POST['note'] ?? '' ) );
            wp_safe_redirect( self::page_url( array( 'view' => 'student', 'id' => $student ) ) . '#eqs-notes' );
            exit;
        }
    }

    // ------------------------------------------------------------ helpers

    private static function e( $s ) {
        return esc_html( (string) $s );
    }

    private static function link( $text, $args, $class = '' ) {
        return '<a class="' . esc_attr( $class ) . '" href="' . esc_url( self::page_url( $args ) ) . '">' . self::e( $text ) . '</a>';
    }

    private static function back() {
        return '<p class="eqs-back">' . self::link( eqs__( '← School home' ), array() ) . '</p>';
    }

    private static function notice( $text, $type = 'ok' ) {
        return '<div class="eqs-notice eqs-' . esc_attr( $type ) . '">' . self::e( $text ) . '</div>';
    }

    private static function bar( $pct ) {
        $pct = max( 0, min( 100, (int) $pct ) );
        return '<span class="eqs-bar"><span style="width:' . $pct . '%"></span></span> ' . $pct . '%';
    }

    // ------------------------------------------------------------ page

    public static function render() {
        if ( ! is_user_logged_in() ) {
            return '<div class="eqs">' . self::notice( eqs__( 'Please log in to see your school dashboard.' ), 'info' ) .
                '<p><a class="eqs-btn" href="' . esc_url( wp_login_url( self::page_url() ) ) . '">' . esc_html( eqs__( 'Log in' ) ) . '</a></p></div>';
        }
        if ( ! eqs_tutor_ready() ) {
            return '<div class="eqs">' . self::notice( eqs__( 'Tutor LMS is not active.' ), 'err' ) . '</div>';
        }
        $view = sanitize_key( $_GET['view'] ?? '' );
        $out  = '';
        switch ( $view ) {
            case 'group':
                $out = self::view_group( absint( $_GET['id'] ?? 0 ), absint( $_GET['subject'] ?? 0 ) );
                break;
            case 'attendance':
                $out = self::view_attendance( absint( $_GET['group'] ?? 0 ), sanitize_text_field( wp_unslash( $_GET['date'] ?? '' ) ) );
                break;
            case 'student':
                $out = self::view_student( absint( $_GET['id'] ?? 0 ) );
                break;
            case 'remarks':
                $out = self::view_remarks( absint( $_GET['group'] ?? 0 ), absint( $_GET['term'] ?? 0 ) );
                break;
            case 'insights':
                $out = self::view_insights( absint( $_GET['group'] ?? 0 ), absint( $_GET['subject'] ?? 0 ) );
                break;
            case 'report':
                $out = self::view_report( absint( $_GET['student'] ?? 0 ), absint( $_GET['group'] ?? 0 ), absint( $_GET['term'] ?? 0 ) );
                break;
            default:
                $out = self::view_home();
        }
        $urdu = 0 === strpos( determine_locale(), 'ur' );
        return '<div class="eqs"' . ( $urdu ? ' dir="rtl" lang="ur"' : '' ) . '>' . eduqode_language_switch_html() . $out . '</div>';
    }

    private static function denied() {
        return self::back() . self::notice( eqs__( 'You do not have access to this page.' ), 'err' );
    }

    // ------------------------------------------------------------ home

    private static function view_home() {
        $me    = get_current_user_id();
        $roles = EQS_Model::roles( $me );
        $h     = '<h2>' . esc_html( eqs__( 'School' ) ) . '</h2>';
        if ( ! $roles ) {
            return $h . self::notice( eqs__( 'Your account is not linked to a class yet. Please contact the school office.' ), 'info' );
        }
        if ( in_array( 'parent', $roles, true ) ) {
            $h .= '<h3>' . esc_html( eqs__( 'My children' ) ) . '</h3><div class="eqs-cards">';
            foreach ( EQS_Model::children( $me ) as $c ) {
                $groups = EQS_Model::student_groups( $c->student_id );
                $att    = EQS_Attendance::summary( $c->student_id, gmdate( 'Y-m-d', strtotime( '-30 days', current_time( 'timestamp' ) ) ), current_time( 'Y-m-d' ) );
                $h     .= '<div class="eqs-card"><h4>' . self::e( $c->display_name ) . '</h4><p>' .
                    self::e( implode( ', ', array_map( array( 'EQS_Model', 'label' ), $groups ) ) ?: eqs__( 'No class yet' ) ) . '</p>' .
                    '<p>' . esc_html( eqs__( 'Attendance (30 days):' ) ) . ' <strong>' . self::e( EQS_Stats::fmt( $att['percent'] ) ) . '</strong></p>' .
                    '<p>' . self::link( eqs__( 'Progress, results and report cards' ), array( 'view' => 'student', 'id' => $c->student_id ), 'eqs-btn' ) . '</p></div>';
            }
            $h .= '</div>';
        }
        if ( in_array( 'student', $roles, true ) ) {
            $h .= '<h3>' . esc_html( eqs__( 'My classes' ) ) . '</h3><p>' . self::link( eqs__( 'My progress, attendance and report cards' ), array( 'view' => 'student', 'id' => $me ), 'eqs-btn' ) . '</p>';
        }
        $groups = in_array( 'principal', $roles, true ) ? EQS_Model::groups() : EQS_Model::teacher_groups( $me );
        if ( $groups && ( in_array( 'principal', $roles, true ) || in_array( 'teacher', $roles, true ) ) ) {
            $h .= '<h3>' . ( in_array( 'principal', $roles, true ) ? eqs__( 'All classes and batches' ) : eqs__( 'My classes' ) ) . '</h3>';
            $h .= '<div class="eqs-scroll"><table class="eqs-table"><thead><tr><th>' . esc_html( eqs__( 'Group' ) ) . '</th><th>' . esc_html( eqs__( 'Students' ) ) . '</th><th>' . esc_html( eqs__( 'Subjects' ) ) . '</th><th>' . esc_html( eqs__( 'Present today' ) ) . '</th><th></th></tr></thead><tbody>';
            foreach ( $groups as $g ) {
                $h .= '<tr><td>' . self::link( EQS_Model::label( $g ), array( 'view' => 'group', 'id' => $g->id ) ) .
                    ' <small>' . self::e( EQS_Model::kind_label( $g->kind ) ) . ( $g->session ? ' · ' . self::e( $g->session ) : '' ) . '</small></td>' .
                    '<td>' . count( EQS_Model::students( $g->id ) ) . '</td><td>' . count( EQS_Model::subjects( $g->id ) ) . '</td>' .
                    '<td>' . self::e( EQS_Stats::fmt( EQS_Attendance::today_percent( $g->id ) ) ) . '</td>' .
                    '<td>' . self::link( eqs__( 'Attendance' ), array( 'view' => 'attendance', 'group' => $g->id ), 'eqs-btn eqs-small' ) . ' ' .
                    self::link( eqs__( 'Learning insights' ), array( 'view' => 'insights', 'group' => $g->id ), 'eqs-btn eqs-small' ) . '</td></tr>';
            }
            $h .= '</tbody></table></div>';
        }
        return $h;
    }

    // ------------------------------------------------------------ group results

    private static function view_group( $group_id, $subject_id ) {
        $g = EQS_Model::group( $group_id );
        if ( ! $g || ! EQS_Model::can_see_group( $group_id ) ) {
            return self::denied();
        }
        $subjects = EQS_Model::subjects( $group_id );
        $students = EQS_Model::students( $group_id );
        $h        = self::back() . '<h2>' . self::e( EQS_Model::label( $g ) ) . '</h2>';
        $h       .= '<p class="eqs-tabs">' . self::link( eqs__( 'All subjects' ), array( 'view' => 'group', 'id' => $group_id ), $subject_id ? '' : 'on' );
        foreach ( $subjects as $s ) {
            $h .= ' ' . self::link( EQS_Model::subject_name( $s ), array( 'view' => 'group', 'id' => $group_id, 'subject' => $s->id ), (int) $s->id === $subject_id ? 'on' : '' );
        }
        $h .= ' · ' . self::link( eqs__( 'Take attendance' ), array( 'view' => 'attendance', 'group' => $group_id ) ) .
            ' · ' . self::link( eqs__( 'Learning insights' ), array( 'view' => 'insights', 'group' => $group_id ) ) . '</p>';
        if ( ! $students ) {
            return $h . self::notice( eqs__( 'No students in this group yet.' ), 'info' );
        }

        if ( $subject_id ) {
            $s = EQS_Model::subject( $subject_id );
            if ( ! $s || ! in_array( (int) $s->id, array_map( 'intval', wp_list_pluck( $subjects, 'id' ) ), true ) ) {
                return self::denied();
            }
            $h  .= '<h3>' . self::e( EQS_Model::subject_name( $s ) ) . '</h3>';
            $h  .= '<div class="eqs-scroll"><table class="eqs-table"><thead><tr><th>' . esc_html( eqs__( 'Roll' ) ) . '</th><th>' . esc_html( eqs__( 'Student' ) ) . '</th><th>' . esc_html( eqs__( 'Progress' ) ) . '</th><th>' . esc_html( eqs__( 'Quizzes' ) ) . '</th><th>' . esc_html( eqs__( 'Quiz avg' ) ) . '</th><th>' . esc_html( eqs__( 'Assignments' ) ) . '</th><th>' . esc_html( eqs__( 'Assignment avg' ) ) . '</th><th>' . esc_html( eqs__( 'Overall' ) ) . '</th><th>' . esc_html( eqs__( 'Grade' ) ) . '</th></tr></thead><tbody>';
            $sum = array();
            foreach ( $students as $st ) {
                $r = EQS_Stats::subject( $s->course_id, $st->user_id );
                if ( null !== $r['overall'] ) {
                    $sum[] = $r['overall'];
                }
                $h .= '<tr><td>' . self::e( $st->roll_no ) . '</td><td>' . self::link( $st->display_name, array( 'view' => 'student', 'id' => $st->user_id ) ) . '</td>' .
                    '<td>' . self::bar( $r['progress'] ) . '</td><td>' . $r['quizzes_taken'] . '/' . $r['quizzes_total'] . '</td><td>' . self::e( EQS_Stats::fmt( $r['quiz_avg'] ) ) . '</td>' .
                    '<td>' . $r['asg_submitted'] . '/' . $r['asg_total'] . ( $r['asg_graded'] < $r['asg_submitted'] ? ' <small>' . self::e( sprintf( eqs__( '(%d to grade)' ), $r['asg_submitted'] - $r['asg_graded'] ) ) . '</small>' : '' ) . '</td>' .
                    '<td>' . self::e( EQS_Stats::fmt( $r['asg_avg'] ) ) . '</td><td><strong>' . self::e( EQS_Stats::fmt( $r['overall'] ) ) . '</strong></td><td>' . self::e( $r['grade'] ) . '</td></tr>';
            }
            $avg = $sum ? round( array_sum( $sum ) / count( $sum ), 1 ) : null;
            $h  .= '</tbody><tfoot><tr><td></td><td>' . esc_html( eqs__( 'Class average' ) ) . '</td><td colspan="5"></td><td><strong>' . self::e( EQS_Stats::fmt( $avg ) ) . '</strong></td><td>' . self::e( null === $avg ? '' : EQS_Stats::grade( $avg ) ) . '</td></tr></tfoot></table></div>';
            return $h;
        }

        // All subjects: overall result per subject, and each student's average.
        $h .= '<div class="eqs-scroll"><table class="eqs-table"><thead><tr><th>' . esc_html( eqs__( 'Roll' ) ) . '</th><th>' . esc_html( eqs__( 'Student' ) ) . '</th>';
        foreach ( $subjects as $s ) {
            $h .= '<th>' . self::e( EQS_Model::subject_name( $s ) ) . '</th>';
        }
        $h  .= '<th>' . esc_html( eqs__( 'Average' ) ) . '</th><th>' . esc_html( eqs__( 'Attendance' ) ) . '</th></tr></thead><tbody>';
        $col = array();
        foreach ( $students as $st ) {
            $h  .= '<tr><td>' . self::e( $st->roll_no ) . '</td><td>' . self::link( $st->display_name, array( 'view' => 'student', 'id' => $st->user_id ) ) . '</td>';
            $all = array();
            foreach ( $subjects as $i => $s ) {
                $r = EQS_Stats::subject( $s->course_id, $st->user_id );
                if ( null !== $r['overall'] ) {
                    $all[]       = $r['overall'];
                    $col[ $i ][] = $r['overall'];
                }
                $h .= '<td>' . self::e( EQS_Stats::fmt( $r['overall'] ) ) . ' <small>' . (int) $r['progress'] . '' . esc_html( eqs__( '% done' ) ) . '</small></td>';
            }
            $avg = $all ? round( array_sum( $all ) / count( $all ), 1 ) : null;
            $att = EQS_Attendance::summary( $st->user_id, '', '', array( $group_id, (int) $g->parent_id, (int) $st->group_id ) );
            $h  .= '<td><strong>' . self::e( EQS_Stats::fmt( $avg ) ) . '</strong> ' . self::e( null === $avg ? '' : EQS_Stats::grade( $avg ) ) . '</td><td>' . self::e( EQS_Stats::fmt( $att['percent'] ) ) . '</td></tr>';
        }
        $h .= '</tbody><tfoot><tr><td></td><td>' . esc_html( eqs__( 'Class average' ) ) . '</td>';
        foreach ( $subjects as $i => $s ) {
            $h .= '<td>' . self::e( EQS_Stats::fmt( empty( $col[ $i ] ) ? null : round( array_sum( $col[ $i ] ) / count( $col[ $i ] ), 1 ) ) ) . '</td>';
        }
        $h .= '<td></td><td></td></tr></tfoot></table></div>';

        $terms = EQS_Reports::terms();
        if ( $terms ) {
            $h .= '<h3>' . esc_html( eqs__( 'Report cards' ) ) . '</h3><p>';
            foreach ( $terms as $t ) {
                $h .= self::link( eqs__( 'Remarks: ' ) . $t->name, array( 'view' => 'remarks', 'group' => $group_id, 'term' => $t->id ), 'eqs-btn eqs-small' ) . ' ';
            }
            $h .= '</p>';
        }
        return $h;
    }

    // ------------------------------------------------------------ attendance

    private static function view_attendance( $group_id, $date ) {
        $g = EQS_Model::group( $group_id );
        if ( ! $g || ! EQS_Model::can_mark_attendance( $group_id ) ) {
            return self::denied();
        }
        $date     = EQS_Attendance::valid_date( $date ) ? $date : current_time( 'Y-m-d' );
        $students = EQS_Model::students( $group_id );
        $day      = EQS_Attendance::day( $group_id, $date );
        $h        = self::back() . '<h2>' . esc_html( eqs__( 'Attendance ·' ) ) . ' ' . self::e( EQS_Model::label( $g ) ) . '</h2>';
        if ( isset( $_GET['saved'] ) ) {
            $h .= self::notice( sprintf( eqs__( 'Saved for %d student(s).' ), absint( $_GET['saved'] ) ) );
        }
        $h .= '<form method="get" class="eqs-inline" action="' . esc_url( self::page_url() ) . '"><input type="hidden" name="view" value="attendance"><input type="hidden" name="group" value="' . (int) $group_id . '">' .
            ( get_option( 'permalink_structure' ) ? '' : '<input type="hidden" name="page_id" value="' . (int) get_option( 'eqs_page_id' ) . '">' ) .
            '<label>' . esc_html( eqs__( 'Date' ) ) . ' <input type="date" name="date" value="' . esc_attr( $date ) . '" max="' . esc_attr( current_time( 'Y-m-d' ) ) . '"></label> <button class="eqs-btn eqs-small">' . esc_html( eqs__( 'Open' ) ) . '</button></form>';
        if ( ! $students ) {
            return $h . self::notice( eqs__( 'No students in this group yet.' ), 'info' );
        }
        $h .= '<form method="post" action="' . esc_url( self::page_url( array( 'view' => 'attendance', 'group' => $group_id, 'date' => $date ) ) ) . '">' .
            wp_nonce_field( 'eqs_attendance_' . $group_id, '_wpnonce', true, false ) .
            '<input type="hidden" name="eqs_action" value="attendance"><input type="hidden" name="group" value="' . (int) $group_id . '"><input type="hidden" name="date" value="' . esc_attr( $date ) . '">' .
            '<p><button type="button" class="eqs-btn eqs-small" onclick="document.querySelectorAll(\'.eqs-att input[value=P]\').forEach(function(r){r.checked=true})">' . esc_html( eqs__( 'All present' ) ) . '</button></p>' .
            '<div class="eqs-scroll"><table class="eqs-table eqs-att"><thead><tr><th>' . esc_html( eqs__( 'Roll' ) ) . '</th><th>' . esc_html( eqs__( 'Student' ) ) . '</th>';
        foreach ( EQS_Attendance::STATUSES as $label ) {
            $h .= '<th>' . self::e( EQS_Attendance::label( $label ) ) . '</th>';
        }
        $h .= '</tr></thead><tbody>';
        foreach ( $students as $st ) {
            $cur = $day[ (int) $st->user_id ] ?? '';
            $h  .= '<tr><td>' . self::e( $st->roll_no ) . '</td><td>' . self::e( $st->display_name ) . '</td>';
            foreach ( array_keys( EQS_Attendance::STATUSES ) as $code ) {
                $h .= '<td><input type="radio" aria-label="' . esc_attr( EQS_Attendance::label( EQS_Attendance::STATUSES[ $code ] ) ) . '" name="status[' . (int) $st->user_id . ']" value="' . $code . '"' . checked( $cur, $code, false ) . '></td>';
            }
            $h .= '</tr>';
        }
        $h .= '</tbody></table></div><p><button type="submit" class="eqs-btn">' . esc_html( eqs__( 'Save attendance' ) ) . '</button></p></form>';
        return $h;
    }

    // ------------------------------------------------------------ one student

    private static function view_student( $student_id ) {
        if ( ! get_userdata( $student_id ) || ! EQS_Model::can_see_student( $student_id ) ) {
            return self::denied();
        }
        $h      = self::back() . '<h2>' . self::e( get_the_author_meta( 'display_name', $student_id ) ) . '</h2>';
        $groups = EQS_Model::student_groups( $student_id );
        if ( ! $groups ) {
            return $h . self::notice( eqs__( 'Not in a class yet.' ), 'info' );
        }
        $att = EQS_Attendance::summary( $student_id );
        $h  .= '<p class="eqs-stats"><span>' . esc_html( eqs__( 'Attendance' ) ) . ' <strong>' . self::e( EQS_Stats::fmt( $att['percent'] ) ) . '</strong></span>' .
            '<span>' . esc_html( eqs__( 'Present' ) ) . ' ' . ( $att['P'] + $att['L'] ) . '</span><span>' . esc_html( eqs__( 'Absent' ) ) . ' ' . $att['A'] . '</span><span>' . esc_html( eqs__( 'Leave' ) ) . ' ' . $att['E'] . '</span></p>';
        $terms = EQS_Reports::terms();
        foreach ( $groups as $g ) {
            $h .= '<h3>' . self::e( EQS_Model::label( $g ) ) . ( $g->roll_no ? ' <small>' . esc_html( eqs__( 'Roll' ) ) . ' ' . self::e( $g->roll_no ) . '</small>' : '' ) . '</h3>';
            $h .= '<div class="eqs-scroll"><table class="eqs-table"><thead><tr><th>' . esc_html( eqs__( 'Subject' ) ) . '</th><th>' . esc_html( eqs__( 'Progress' ) ) . '</th><th>' . esc_html( eqs__( 'Quizzes' ) ) . '</th><th>' . esc_html( eqs__( 'Quiz avg' ) ) . '</th><th>' . esc_html( eqs__( 'Assignments' ) ) . '</th><th>' . esc_html( eqs__( 'Assignment avg' ) ) . '</th><th>' . esc_html( eqs__( 'Overall' ) ) . '</th><th>' . esc_html( eqs__( 'Grade' ) ) . '</th></tr></thead><tbody>';
            foreach ( EQS_Model::subjects( $g->id ) as $s ) {
                $r  = EQS_Stats::subject( $s->course_id, $student_id );
                $h .= '<tr><td>' . self::e( EQS_Model::subject_name( $s ) ) . '</td><td>' . self::bar( $r['progress'] ) . '</td><td>' . $r['quizzes_taken'] . '/' . $r['quizzes_total'] . '</td>' .
                    '<td>' . self::e( EQS_Stats::fmt( $r['quiz_avg'] ) ) . '</td><td>' . $r['asg_submitted'] . '/' . $r['asg_total'] . '</td><td>' . self::e( EQS_Stats::fmt( $r['asg_avg'] ) ) . '</td>' .
                    '<td><strong>' . self::e( EQS_Stats::fmt( $r['overall'] ) ) . '</strong></td><td>' . self::e( $r['grade'] ) . '</td></tr>';
            }
            $h .= '</tbody></table></div>';
            if ( $terms ) {
                $h .= '<p>' . esc_html( eqs__( 'Report cards:' ) ) . ' ';
                foreach ( $terms as $t ) {
                    $h .= self::link( $t->name, array( 'view' => 'report', 'student' => $student_id, 'group' => $g->id, 'term' => $t->id ), 'eqs-btn eqs-small' ) . ' ';
                }
                $h .= '</p>';
            }
        }
        $h     .= self::learner_insights( $student_id, $groups );
        $recent = EQS_Attendance::recent( $student_id, 14 );
        if ( $recent ) {
            $h .= '<h3>' . esc_html( eqs__( 'Recent attendance' ) ) . '</h3><p class="eqs-days">';
            foreach ( $recent as $r ) {
                $h .= '<span class="eqs-day eqs-' . esc_attr( strtolower( $r->status ) ) . '" title="' . esc_attr( EQS_Attendance::label( EQS_Attendance::STATUSES[ $r->status ] ?? '' ) ) . '">' .
                    self::e( date_i18n( 'j M', strtotime( $r->att_date ) ) ) . ' · ' . self::e( $r->status ) . '</span>';
            }
            $h .= '</p>';
        }
        return $h;
    }

    // ------------------------------------------------------------ learning insights

    private static function chip( $status ) {
        return '<span class="eqs-chip eqs-st-' . esc_attr( $status ) . '">' . self::e( EQS_Insights::status_label( $status ) ) . '</span>';
    }

    private static function patterns_html( $patterns, $with_reason = true ) {
        $h = '';
        foreach ( $patterns as $p ) {
            if ( 'not_started' === $p[0] ) {
                continue;
            }
            $h .= '<span class="eqs-pattern eqs-p-' . esc_attr( $p[0] ) . '" title="' . esc_attr( $p[2] ) . '">' . self::e( $p[1] ) . '</span> ';
            if ( $with_reason && $p[2] ) {
                $h .= '<small>' . self::e( $p[2] ) . '</small><br>';
            }
        }
        return $h;
    }

    private static function ago( $ts ) {
        if ( ! $ts ) {
            return '—';
        }
        $d = (int) floor( ( time() - $ts ) / DAY_IN_SECONDS );
        return 0 === $d ? eqs__( 'today' ) : sprintf( eqs__( '%d day(s) ago' ), $d );
    }

    private static function view_insights( $group_id, $subject_id ) {
        $g = EQS_Model::group( $group_id );
        if ( ! $g || ! EQS_Model::can_see_group( $group_id ) ) {
            return self::denied();
        }
        $subjects = EQS_Model::subjects( $group_id );
        $h        = self::back() . '<h2>' . self::e( EQS_Model::label( $g ) ) . ' · ' . esc_html( eqs__( 'Learning insights' ) ) . '</h2>';
        if ( ! $subjects ) {
            return $h . self::notice( eqs__( 'No courses linked to this group yet.' ), 'info' );
        }
        $s = $subject_id ? EQS_Model::subject( $subject_id ) : $subjects[0];
        if ( ! $s || ! in_array( (int) $s->id, array_map( 'intval', wp_list_pluck( $subjects, 'id' ) ), true ) ) {
            return self::denied();
        }
        $h .= '<p class="eqs-tabs">';
        foreach ( $subjects as $x ) {
            $h .= self::link( EQS_Model::subject_name( $x ), array( 'view' => 'insights', 'group' => $group_id, 'subject' => $x->id ), (int) $x->id === (int) $s->id ? 'on' : '' ) . ' ';
        }
        $h .= '</p>';

        $team = EQS_Insights::team( $group_id, $s->course_id );
        if ( ! $team['rows'] ) {
            return $h . self::notice( eqs__( 'No students in this group yet.' ), 'info' );
        }
        $count = array( 'stuck' => 0, 'help' => 0, 'good' => 0, 'none' => 0 );
        foreach ( $team['rows'] as $r ) {
            $count[ $r['data']['status'] ]++;
        }
        $h .= '<p class="eqs-stats">';
        foreach ( $count as $st => $n ) {
            $h .= '<span>' . self::chip( $st ) . ' <strong>' . (int) $n . '</strong></span>';
        }
        $h .= '</p><p><small>' . esc_html( eqs__( 'Patterns are signs to look at, not judgements. Talk to the person and write what you learn as a note on their page.' ) ) . '</small></p>';

        $h .= '<div class="eqs-scroll"><table class="eqs-table eqs-insights"><thead><tr><th>' . esc_html( eqs__( 'Name' ) ) . '</th><th>' . esc_html( eqs__( 'ID' ) ) . '</th><th>' . esc_html( eqs__( 'Experience' ) ) . '</th><th>' .
            esc_html( eqs__( 'Status' ) ) . '</th><th>' . esc_html( eqs__( 'Progress' ) ) . '</th><th>' . esc_html( eqs__( 'Quizzes passed' ) ) . '</th><th>' . esc_html( eqs__( 'Attempts to pass' ) ) . '</th><th>' .
            esc_html( eqs__( 'Best score' ) ) . '</th><th>' . esc_html( eqs__( 'Seconds per question' ) ) . '</th><th>' . esc_html( eqs__( 'Time spent' ) ) . '</th><th>' . esc_html( eqs__( 'Last active' ) ) . '</th><th>' . esc_html( eqs__( 'Patterns' ) ) . '</th></tr></thead><tbody>';
        foreach ( $team['rows'] as $r ) {
            $d  = $r['data'];
            $h .= '<tr class="eqs-row-' . esc_attr( $d['status'] ) . '"><td>' . self::link( $r['name'], array( 'view' => 'student', 'id' => $r['user'] ) ) . '</td><td>' . self::e( $r['id_no'] ) . '</td>' .
                '<td>' . self::e( null === $r['years'] ? '—' : sprintf( eqs__( '%s yr' ), EQS_Stats::fmt( $r['years'] ) ) ) . '</td><td>' . self::chip( $d['status'] ) . '</td>' .
                '<td>' . self::bar( $d['progress'] ) . '</td><td>' . (int) $d['quizzes_passed'] . '/' . (int) $d['quizzes_total'] . '</td>' .
                '<td>' . self::e( EQS_Stats::fmt( $d['attempts_to_pass'] ) ) . ( $d['failed'] ? ' <small>' . self::e( sprintf( eqs__( '(%d failed)' ), $d['failed'] ) ) . '</small>' : '' ) . '</td>' .
                '<td>' . self::e( null === $d['best_avg'] ? '—' : EQS_Stats::fmt( $d['best_avg'] ) . '%' ) . '</td><td>' . self::e( EQS_Stats::fmt( $d['seconds_per_q'] ) ) . '</td>' .
                '<td>' . self::e( sprintf( eqs__( '%d min' ), $d['minutes'] ) ) . '</td><td>' . self::e( self::ago( $d['last_activity'] ) ) . '</td>' .
                '<td class="eqs-wrap">' . self::patterns_html( $d['patterns'], false ) . '</td></tr>';
        }
        $h .= '</tbody>';
        if ( $team['median_seconds'] ) {
            $h .= '<tfoot><tr><td colspan="8">' . esc_html( eqs__( 'Middle value of the group' ) ) . '</td><td><strong>' . self::e( EQS_Stats::fmt( $team['median_seconds'] ) ) . '</strong></td><td colspan="3"></td></tr></tfoot>';
        }
        $h .= '</table></div>';

        $hard = EQS_Insights::hard_questions( $s->course_id, array_column( $team['rows'], 'user' ), 8 );
        if ( $hard ) {
            $h .= '<h3>' . esc_html( eqs__( 'Questions this group gets wrong most' ) ) . '</h3><p><small>' . esc_html( eqs__( 'When most of the group gets a question wrong, the lesson (or the question) needs fixing, not the people.' ) ) . '</small></p>';
            $h .= '<div class="eqs-scroll"><table class="eqs-table"><thead><tr><th>' . esc_html( eqs__( 'Question' ) ) . '</th><th>' . esc_html( eqs__( 'Topic' ) ) . '</th><th>' . esc_html( eqs__( 'Wrong' ) ) . '</th></tr></thead><tbody>';
            foreach ( $hard as $q ) {
                $h .= '<tr><td class="eqs-wrap">' . self::e( $q['question'] ) . '<br><small>' . self::e( $q['quiz'] ) . '</small></td><td>' . self::e( $q['topic'] ) . '</td><td>' . self::bar( $q['percent'] ) . ' <small>' . (int) $q['wrong'] . '/' . (int) $q['total'] . '</small></td></tr>';
            }
            $h .= '</tbody></table></div>';
        }

        if ( count( $team['bands'] ) > 1 || ( $team['bands'] && '' !== $team['bands'][0]['band'] ) ) {
            $h .= '<h3>' . esc_html( eqs__( 'By years of experience' ) ) . '</h3><div class="eqs-scroll"><table class="eqs-table"><thead><tr><th>' . esc_html( eqs__( 'Experience' ) ) . '</th><th>' . esc_html( eqs__( 'People' ) ) . '</th><th>' . esc_html( eqs__( 'Best score (average)' ) ) . '</th><th>' . esc_html( eqs__( 'Attempts to pass (average)' ) ) . '</th></tr></thead><tbody>';
            foreach ( $team['bands'] as $b ) {
                $h .= '<tr><td>' . self::e( '' === $b['band'] ? eqs__( 'Not set' ) : sprintf( eqs__( '%s years' ), $b['band'] ) ) . '</td><td>' . (int) $b['count'] . '</td><td>' . self::e( null === $b['score'] ? '—' : EQS_Stats::fmt( $b['score'] ) . '%' ) . '</td><td>' . self::e( EQS_Stats::fmt( $b['attempts'] ) ) . '</td></tr>';
            }
            $h .= '</tbody></table></div>';
        }
        return $h;
    }

    /** On a learner's page: status and patterns per course, questions they get wrong, supervisor notes. */
    private static function learner_insights( $student_id, $groups ) {
        $h    = '<h3>' . esc_html( eqs__( 'Learning insights' ) ) . '</h3>';
        $seen = array();
        foreach ( $groups as $g ) {
            foreach ( EQS_Model::subjects( $g->id ) as $s ) {
                if ( isset( $seen[ $s->course_id ] ) ) {
                    continue;
                }
                $seen[ $s->course_id ] = true;
                $d     = EQS_Insights::learner( $s->course_id, $student_id );
                $h    .= '<div class="eqs-card eqs-insight"><h4>' . self::e( EQS_Model::subject_name( $s ) ) . ' ' . self::chip( $d['status'] ) . '</h4>';
                $h    .= '<p class="eqs-stats"><span>' . esc_html( eqs__( 'Quizzes passed' ) ) . ' <strong>' . (int) $d['quizzes_passed'] . '/' . (int) $d['quizzes_total'] . '</strong></span>' .
                    '<span>' . esc_html( eqs__( 'Attempts to pass' ) ) . ' <strong>' . self::e( EQS_Stats::fmt( $d['attempts_to_pass'] ) ) . '</strong></span>' .
                    '<span>' . esc_html( eqs__( 'Seconds per question' ) ) . ' <strong>' . self::e( EQS_Stats::fmt( $d['seconds_per_q'] ) ) . '</strong></span>' .
                    '<span>' . esc_html( eqs__( 'Time spent' ) ) . ' <strong>' . self::e( sprintf( eqs__( '%d min' ), $d['minutes'] ) ) . '</strong></span>' .
                    '<span>' . esc_html( eqs__( 'Last active' ) ) . ' <strong>' . self::e( self::ago( $d['last_activity'] ) ) . '</strong></span></p>';
                $h    .= '<p>' . self::patterns_html( $d['patterns'] ) . '</p>';
                $wrong = EQS_Insights::hard_questions( $s->course_id, array( $student_id ), 5, 1 );
                if ( $wrong ) {
                    $h .= '<p><strong>' . esc_html( eqs__( 'Gets these wrong:' ) ) . '</strong></p><ul>';
                    foreach ( $wrong as $q ) {
                        $h .= '<li>' . self::e( $q['question'] ) . ' <small>(' . self::e( $q['topic'] ) . ' · ' . (int) $q['wrong'] . '/' . (int) $q['total'] . ')</small></li>';
                    }
                    $h .= '</ul>';
                }
                $h .= '</div>';
            }
        }
        if ( EQS_Insights::can_use_notes( $student_id ) ) {
            $h .= '<h3 id="eqs-notes">' . esc_html( eqs__( 'Supervisor notes' ) ) . '</h3><p><small>' . esc_html( eqs__( 'Why is this person fast or slow? Write what you learned from talking to them. Only supervisors see these notes.' ) ) . '</small></p>';
            $h .= '<form method="post" class="eqs-remark">' . wp_nonce_field( 'eqs_note_' . $student_id, '_wpnonce', true, false ) .
                '<input type="hidden" name="eqs_action" value="note"><input type="hidden" name="student" value="' . (int) $student_id . '">' .
                '<textarea name="note" rows="3" placeholder="' . esc_attr( eqs__( 'For example: cannot read English well, learns better on the machine; or: night shift, studies at the start of the break.' ) ) . '"></textarea>' .
                '<button type="submit" class="eqs-btn eqs-small">' . esc_html( eqs__( 'Add note' ) ) . '</button></form>';
            foreach ( EQS_Insights::notes( $student_id ) as $n ) {
                $h .= '<div class="eqs-remarkbox"><small>' . self::e( $n->author ) . ' · ' . self::e( date_i18n( 'j M Y', strtotime( $n->created_at . ' UTC' ) ) ) . '</small><br>' . nl2br( self::e( $n->note ) ) . '</div>';
            }
        }
        return $h;
    }

    // ------------------------------------------------------------ report cards

    private static function view_remarks( $group_id, $term_id ) {
        $g = EQS_Model::group( $group_id );
        $t = EQS_Reports::term( $term_id );
        if ( ! $g || ! $t || ! EQS_Model::can_see_group( $group_id ) ) {
            return self::denied();
        }
        $h = self::back() . '<h2>' . esc_html( eqs__( 'Report card remarks ·' ) ) . ' ' . self::e( EQS_Model::label( $g ) ) . ' · ' . self::e( $t->name ) . '</h2>';
        if ( isset( $_GET['saved'] ) ) {
            $h .= self::notice( eqs__( 'Remarks saved.' ) );
        }
        $h .= '<form method="post" action="' . esc_url( self::page_url( array( 'view' => 'remarks', 'group' => $group_id, 'term' => $term_id ) ) ) . '">' .
            wp_nonce_field( 'eqs_remarks_' . $group_id . '_' . $term_id, '_wpnonce', true, false ) .
            '<input type="hidden" name="eqs_action" value="remarks"><input type="hidden" name="group" value="' . (int) $group_id . '"><input type="hidden" name="term" value="' . (int) $term_id . '">';
        foreach ( EQS_Model::students( $group_id ) as $st ) {
            $h .= '<div class="eqs-remark"><label><strong>' . self::e( trim( $st->roll_no . ' ' . $st->display_name ) ) . '</strong> · ' .
                self::link( eqs__( 'report card' ), array( 'view' => 'report', 'student' => $st->user_id, 'group' => $st->group_id, 'term' => $term_id ) ) .
                '<textarea name="remark[' . (int) $st->user_id . ']" rows="2">' . esc_textarea( EQS_Reports::remark( $term_id, $group_id, $st->user_id ) ) . '</textarea></label></div>';
        }
        return $h . '<p><button type="submit" class="eqs-btn">' . esc_html( eqs__( 'Save remarks' ) ) . '</button></p></form>';
    }

    private static function view_report( $student_id, $group_id, $term_id ) {
        if ( ! EQS_Model::can_see_student( $student_id ) ) {
            return self::denied();
        }
        $in_group = in_array( (int) $group_id, array_map( 'intval', wp_list_pluck( EQS_Model::student_groups( $student_id ), 'id' ) ), true );
        $c        = $in_group ? EQS_Reports::card( $student_id, $group_id, $term_id ) : null;
        if ( ! $c ) {
            return self::denied();
        }
        // The class teacher's remark may be written for the section or for the whole class.
        $remark = $c['remark'];
        if ( '' === $remark && ( $g = EQS_Model::group( $group_id ) ) && $g->parent_id ) {
            $remark = EQS_Reports::remark( $term_id, $g->parent_id, $student_id );
        }
        $school = get_bloginfo( 'name' );
        $h      = '<p class="eqs-back eqs-noprint">' . self::link( eqs__( '← Back' ), array( 'view' => 'student', 'id' => $student_id ) ) .
            ' <button type="button" class="eqs-btn eqs-small" onclick="window.print()">' . esc_html( eqs__( 'Print / Save as PDF' ) ) . '</button></p>';
        $h     .= '<div class="eqs-print"><div class="eqs-report"><h2>' . self::e( $school ) . '</h2><h3>' . esc_html( eqs__( 'Report card ·' ) ) . ' ' . self::e( $c['term']->name ) . '</h3>' .
            '<table class="eqs-meta"><tr><td>' . esc_html( eqs__( 'Student' ) ) . '</td><td><strong>' . self::e( $c['student'] ) . '</strong></td><td>' . esc_html( eqs__( 'Class' ) ) . '</td><td>' . self::e( $c['group'] ) . '</td></tr>' .
            '<tr><td>' . esc_html( eqs__( 'Roll no.' ) ) . '</td><td>' . self::e( $c['roll_no'] ) . '</td><td>' . esc_html( eqs__( 'Term' ) ) . '</td><td>' . self::e( date_i18n( 'j M Y', strtotime( $c['term']->start_date ) ) . ' – ' . date_i18n( 'j M Y', strtotime( $c['term']->end_date ) ) ) . '</td></tr></table>' .
            '<table class="eqs-table"><thead><tr><th>' . esc_html( eqs__( 'Subject' ) ) . '</th><th>' . esc_html( eqs__( 'Teacher' ) ) . '</th><th>' . esc_html( eqs__( 'Quizzes' ) ) . '</th><th>' . esc_html( eqs__( 'Assignments' ) ) . '</th><th>' . esc_html( eqs__( 'Overall' ) ) . '</th><th>' . esc_html( eqs__( 'Grade' ) ) . '</th></tr></thead><tbody>';
        foreach ( $c['rows'] as $r ) {
            $h .= '<tr><td>' . self::e( $r['subject'] ) . '</td><td>' . self::e( $r['teacher'] ) . '</td><td>' . self::e( EQS_Stats::fmt( $r['quiz_avg'] ) ) . '</td>' .
                '<td>' . self::e( EQS_Stats::fmt( $r['asg_avg'] ) ) . '</td><td><strong>' . self::e( EQS_Stats::fmt( $r['overall'] ) ) . '</strong></td><td>' . self::e( $r['grade'] ) . '</td></tr>';
        }
        $a  = $c['attendance'];
        $h .= '</tbody><tfoot><tr><td colspan="4">' . esc_html( eqs__( 'Overall' ) ) . '</td><td><strong>' . self::e( EQS_Stats::fmt( $c['overall'] ) ) . '</strong></td><td><strong>' . self::e( $c['grade'] ) . '</strong></td></tr></tfoot></table>' .
            '<p>' . esc_html( eqs__( 'Attendance:' ) ) . ' <strong>' . self::e( EQS_Stats::fmt( $a['percent'] ) ) . '</strong> ' . self::e( sprintf( eqs__( '(%1$d of %2$d days present, %3$d on leave)' ), $a['P'] + $a['L'], (int) $a['days'], (int) $a['E'] ) ) . '</p>' .
            '<div class="eqs-remarkbox"><strong>' . esc_html( eqs__( 'Teacher\'s remarks' ) ) . '</strong><p>' . nl2br( self::e( $remark ?: '—' ) ) . '</p></div>' .
            '<p class="eqs-sign"><span>' . esc_html( eqs__( 'Class teacher' ) ) . '</span><span>' . esc_html( eqs__( 'Principal' ) ) . '</span><span>' . esc_html( eqs__( 'Parent' ) ) . '</span></p></div></div>';
        return $h;
    }
}
