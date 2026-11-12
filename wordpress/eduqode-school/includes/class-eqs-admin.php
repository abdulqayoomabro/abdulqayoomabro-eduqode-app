<?php
/**
 * wp-admin → School: classes/sections/batches, subjects (course + teacher), students,
 * parents (accounts and links) and report card terms/grading.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EQS_Admin {

    const CAP = 'manage_options';

    public static function init() {
        add_action( 'admin_menu', array( __CLASS__, 'menu' ) );
        add_action( 'admin_post_eqs_admin', array( __CLASS__, 'save' ) );
    }

    public static function menu() {
        add_menu_page( eqs__( 'School' ), eqs__( 'School' ), self::CAP, 'eqs-groups', array( __CLASS__, 'page_groups' ), 'dashicons-groups', 31 );
        add_submenu_page( 'eqs-groups', eqs__( 'Classes & batches' ), eqs__( 'Classes & batches' ), self::CAP, 'eqs-groups', array( __CLASS__, 'page_groups' ) );
        add_submenu_page( 'eqs-groups', eqs__( 'Parents' ), eqs__( 'Parents' ), self::CAP, 'eqs-parents', array( __CLASS__, 'page_parents' ) );
        add_submenu_page( 'eqs-groups', eqs__( 'Report cards' ), eqs__( 'Report cards' ), self::CAP, 'eqs-terms', array( __CLASS__, 'page_terms' ) );
    }

    private static function url( $page, $args = array() ) {
        return add_query_arg( $args, admin_url( 'admin.php?page=' . $page ) );
    }

    private static function back( $page, $args, $msg ) {
        wp_safe_redirect( self::url( $page, $args + array( 'msg' => rawurlencode( $msg ) ) ) );
        exit;
    }

    /** "email or username" → user id. */
    private static function find_user( $who ) {
        $who = trim( (string) $who );
        if ( '' === $who ) {
            return 0;
        }
        $u = is_email( $who ) ? get_user_by( 'email', $who ) : get_user_by( 'login', $who );
        return $u ? (int) $u->ID : 0;
    }

    public static function save() {
        if ( ! current_user_can( self::CAP ) ) {
            wp_die( eqs__( 'Not allowed.' ) );
        }
        check_admin_referer( 'eqs_admin' );
        $do = sanitize_key( $_POST['do'] ?? '' );
        $p  = wp_unslash( $_POST );
        switch ( $do ) {
            case 'save_group':
                $id = EQS_Model::save_group( $p );
                self::back( 'eqs-groups', $id ? array( 'group' => $id ) : array(), $id ? eqs__( 'Saved.' ) : eqs__( 'Name is required.' ) );
                break;
            case 'delete_group':
                EQS_Model::delete_group( absint( $p['id'] ?? 0 ) );
                self::back( 'eqs-groups', array(), eqs__( 'Deleted.' ) );
                break;
            case 'add_subject':
                $ok = EQS_Model::add_subject( absint( $p['group'] ), absint( $p['course_id'] ?? 0 ), absint( $p['teacher_id'] ?? 0 ), (string) ( $p['name'] ?? '' ) );
                // Students already in the group get the new course.
                if ( $ok ) {
                    foreach ( EQS_Model::students( absint( $p['group'] ) ) as $st ) {
                        EQS_Model::add_student( $st->group_id, $st->user_id, $st->roll_no );
                    }
                }
                self::back( 'eqs-groups', array( 'group' => absint( $p['group'] ) ), $ok ? eqs__( 'Subject added.' ) : eqs__( 'Choose a course.' ) );
                break;
            case 'delete_subject':
                EQS_Model::delete_subject( absint( $p['id'] ?? 0 ) );
                self::back( 'eqs-groups', array( 'group' => absint( $p['group'] ) ), eqs__( 'Subject removed.' ) );
                break;
            case 'add_students':
                $added = 0;
                $missing = array();
                foreach ( preg_split( '/\r?\n/', (string) ( $p['students'] ?? '' ) ) as $line ) {
                    $parts = array_map( 'trim', explode( ',', $line ) );
                    if ( '' === $parts[0] ) {
                        continue;
                    }
                    $uid = self::find_user( $parts[0] );
                    if ( $uid && EQS_Model::add_student( absint( $p['group'] ), $uid, $parts[1] ?? '' ) ) {
                        $added++;
                    } else {
                        $missing[] = $parts[0];
                    }
                }
                self::back( 'eqs-groups', array( 'group' => absint( $p['group'] ) ),
                    sprintf( eqs__( '%d student(s) added.' ), $added ) . ( $missing ? eqs__( ' Not found: ' ) . implode( ', ', array_slice( $missing, 0, 10 ) ) : '' ) );
                break;
            case 'remove_student':
                EQS_Model::remove_student( absint( $p['member_group'] ?? 0 ), absint( $p['user'] ?? 0 ) );
                self::back( 'eqs-groups', array( 'group' => absint( $p['group'] ) ), eqs__( 'Student removed from the group.' ) );
                break;
            case 'create_parent':
                $email = sanitize_email( $p['email'] ?? '' );
                $name  = sanitize_text_field( $p['name'] ?? '' );
                if ( ! is_email( $email ) ) {
                    self::back( 'eqs-parents', array(), eqs__( 'A valid email is required.' ) );
                }
                $uid = email_exists( $email );
                if ( ! $uid ) {
                    $login = sanitize_user( strstr( $email, '@', true ), true ) ?: 'parent';
                    while ( username_exists( $login ) ) {
                        $login .= wp_rand( 1, 9 );
                    }
                    $uid = wp_insert_user( array( 'user_login' => $login, 'user_email' => $email, 'display_name' => $name ?: $login,
                        'first_name' => $name, 'user_pass' => wp_generate_password( 20 ), 'role' => 'eqs_parent' ) );
                    if ( is_wp_error( $uid ) ) {
                        self::back( 'eqs-parents', array(), $uid->get_error_message() );
                    }
                    if ( ! empty( $p['notify'] ) ) {
                        wp_new_user_notification( $uid, null, 'user' );   // e-mail with a link to set the password
                    }
                }
                $linked = 0;
                foreach ( preg_split( '/[\r\n,]+/', (string) ( $p['children'] ?? '' ) ) as $who ) {
                    $sid = self::find_user( $who );
                    if ( $sid && EQS_Model::link_parent( $uid, $sid, (string) ( $p['relation'] ?? '' ) ) ) {
                        $linked++;
                    }
                }
                self::back( 'eqs-parents', array(), sprintf( eqs__( 'Parent saved, %d child(ren) linked.' ), $linked ) );
                break;
            case 'unlink_parent':
                EQS_Model::unlink_parent( absint( $p['parent'] ?? 0 ), absint( $p['student'] ?? 0 ) );
                self::back( 'eqs-parents', array(), eqs__( 'Link removed.' ) );
                break;
            case 'add_term':
                $ok = EQS_Reports::add_term( (string) ( $p['name'] ?? '' ), (string) ( $p['start'] ?? '' ), (string) ( $p['end'] ?? '' ), (string) ( $p['session'] ?? '' ) );
                self::back( 'eqs-terms', array(), $ok ? eqs__( 'Term added.' ) : eqs__( 'Name, start and end date are required.' ) );
                break;
            case 'delete_term':
                EQS_Reports::delete_term( absint( $p['id'] ?? 0 ) );
                self::back( 'eqs-terms', array(), eqs__( 'Term deleted.' ) );
                break;
            case 'grading':
                update_option( 'eqs_weights', array( 'quiz' => absint( $p['w_quiz'] ?? 50 ), 'assignment' => absint( $p['w_assignment'] ?? 50 ) ) );
                update_option( 'eqs_scale', sanitize_textarea_field( $p['scale'] ?? '' ) );
                self::back( 'eqs-terms', array(), eqs__( 'Grading saved.' ) );
                break;
        }
        self::back( 'eqs-groups', array(), eqs__( 'Nothing changed.' ) );
    }

    private static function form( $do, $fields, $button, $class = 'secondary', $confirm = '' ) {
        $h = '<form method="post" action="' . esc_url( admin_url( 'admin-post.php' ) ) . '" style="display:inline"' .
            ( $confirm ? ' onsubmit="return confirm(\'' . esc_js( $confirm ) . '\')"' : '' ) . '>' .
            wp_nonce_field( 'eqs_admin', '_wpnonce', true, false ) . '<input type="hidden" name="action" value="eqs_admin"><input type="hidden" name="do" value="' . esc_attr( $do ) . '">';
        foreach ( $fields as $k => $v ) {
            $h .= '<input type="hidden" name="' . esc_attr( $k ) . '" value="' . esc_attr( $v ) . '">';
        }
        return $h . '<button class="button button-' . esc_attr( $class ) . '">' . esc_html( $button ) . '</button></form>';
    }

    private static function open( $do, $extra = array() ) {
        $h = '<form method="post" action="' . esc_url( admin_url( 'admin-post.php' ) ) . '">' . wp_nonce_field( 'eqs_admin', '_wpnonce', true, false ) .
            '<input type="hidden" name="action" value="eqs_admin"><input type="hidden" name="do" value="' . esc_attr( $do ) . '">';
        foreach ( $extra as $k => $v ) {
            $h .= '<input type="hidden" name="' . esc_attr( $k ) . '" value="' . esc_attr( $v ) . '">';
        }
        return $h;
    }

    private static function notice() {
        return empty( $_GET['msg'] ) ? '' : '<div class="notice notice-info is-dismissible"><p>' . esc_html( wp_unslash( $_GET['msg'] ) ) . '</p></div>';
    }

    private static function teachers() {
        return get_users( array( 'role__in' => array( 'administrator', 'editor', 'author', 'tutor_instructor' ), 'orderby' => 'display_name', 'fields' => array( 'ID', 'display_name' ) ) );
    }

    private static function select_users( $name, $users, $selected = 0 ) {
        $h = '<select name="' . esc_attr( $name ) . '"><option value="0">—</option>';
        foreach ( $users as $u ) {
            $h .= '<option value="' . (int) $u->ID . '"' . selected( (int) $selected, (int) $u->ID, false ) . '>' . esc_html( $u->display_name ) . '</option>';
        }
        return $h . '</select>';
    }

    // ------------------------------------------------------------ pages

    public static function page_groups() {
        if ( ! empty( $_GET['group'] ) ) {
            self::page_group( absint( $_GET['group'] ) );
            return;
        }
        $groups = EQS_Model::groups();
        $front  = EQS_Front::page_url();
        echo '<div class="wrap"><h1>' . esc_html( eqs__( 'Classes & batches' ) ) . '</h1>' . self::notice();
        echo '<p>' . esc_html( eqs__( 'Dashboards for teachers, parents and students:' ) ) . ' <a href="' . esc_url( $front ) . '" target="_blank">' . esc_html( $front ) . '</a> ' . esc_html( eqs__( '(also inside the EduQode app).' ) ) . '</p>';
        echo '<table class="widefat striped"><thead><tr><th>' . esc_html( eqs__( 'Name' ) ) . '</th><th>' . esc_html( eqs__( 'Type' ) ) . '</th><th>' . esc_html( eqs__( 'Session' ) ) . '</th><th>' . esc_html( eqs__( 'Class teacher' ) ) . '</th><th>' . esc_html( eqs__( 'Students' ) ) . '</th><th>' . esc_html( eqs__( 'Subjects' ) ) . '</th><th></th></tr></thead><tbody>';
        foreach ( $groups as $g ) {
            echo '<tr><td><a href="' . esc_url( self::url( 'eqs-groups', array( 'group' => $g->id ) ) ) . '"><strong>' . esc_html( EQS_Model::label( $g ) ) . '</strong></a></td>' .
                '<td>' . esc_html( EQS_Model::kind_label( $g->kind ) ) . '</td><td>' . esc_html( $g->session ) . '</td>' .
                '<td>' . esc_html( $g->class_teacher_id ? get_the_author_meta( 'display_name', $g->class_teacher_id ) : '' ) . '</td>' .
                '<td>' . count( EQS_Model::students( $g->id ) ) . '</td><td>' . count( EQS_Model::subjects( $g->id ) ) . '</td>' .
                '<td>' . self::form( 'delete_group', array( 'id' => $g->id ), eqs__( 'Delete' ), 'link-delete', eqs__( 'Delete this group, its subjects, students list, attendance and remarks?' ) ) . '</td></tr>'; // phpcs:ignore
        }
        if ( ! $groups ) {
            echo '<tr><td colspan="7">' . esc_html( eqs__( 'No classes yet. Add one below (a school class, a section of a class, or an academy batch).' ) ) . '</td></tr>';
        }
        echo '</tbody></table>';
        self::group_form( null, $groups );
        echo '</div>';
    }

    private static function group_form( $g, $groups ) {
        echo '<h2>' . ( $g ? eqs__( 'Edit' ) : eqs__( 'Add a class, section or batch' ) ) . '</h2>' . self::open( 'save_group', $g ? array( 'id' => $g->id ) : array() ); // phpcs:ignore
        echo '<table class="form-table"><tr><th>' . esc_html( eqs__( 'Type' ) ) . '</th><td><select name="kind">';
        foreach ( EQS_Model::KINDS as $k => $label ) {
            echo '<option value="' . esc_attr( $k ) . '"' . selected( $g ? $g->kind : 'class', $k, false ) . '>' . esc_html( EQS_Model::kind_label( $k ) ) . '</option>';
        }
        echo '</select></td></tr><tr><th>' . esc_html( eqs__( 'Name' ) ) . '</th><td><input name="name" class="regular-text" required value="' . esc_attr( $g ? $g->name : '' ) . '" placeholder="' . esc_attr( eqs__( 'Class 7, A, Morning batch…' ) ) . '"></td></tr>';
        echo '<tr><th>' . esc_html( eqs__( 'Class (for a section)' ) ) . '</th><td><select name="parent_id"><option value="0">—</option>';
        foreach ( $groups as $o ) {
            if ( 'class' === $o->kind && ( ! $g || (int) $o->id !== (int) $g->id ) ) {
                echo '<option value="' . (int) $o->id . '"' . selected( $g ? (int) $g->parent_id : 0, (int) $o->id, false ) . '>' . esc_html( $o->name ) . '</option>';
            }
        }
        echo '</select></td></tr><tr><th>' . esc_html( eqs__( 'Session' ) ) . '</th><td><input name="session" value="' . esc_attr( $g ? $g->session : '' ) . '" placeholder="2026-27"></td></tr>';
        echo '<tr><th>' . esc_html( eqs__( 'Class teacher' ) ) . '</th><td>' . self::select_users( 'class_teacher_id', self::teachers(), $g ? $g->class_teacher_id : 0 ) . '</td></tr>'; // phpcs:ignore
        echo '<tr><th>' . esc_html( eqs__( 'Order' ) ) . '</th><td><input type="number" name="sort" value="' . esc_attr( $g ? $g->sort : 0 ) . '" class="small-text"></td></tr></table>';
        submit_button( $g ? eqs__( 'Save' ) : eqs__( 'Add' ) );
        echo '</form>';
    }

    private static function page_group( $id ) {
        $g = EQS_Model::group( $id );
        if ( ! $g ) {
            echo '<div class="wrap"><p>' . esc_html( eqs__( 'Not found.' ) ) . '</p></div>';
            return;
        }
        echo '<div class="wrap"><h1>' . esc_html( EQS_Model::label( $g ) ) . '</h1>' . self::notice() . '<p><a href="' . esc_url( self::url( 'eqs-groups' ) ) . '">' . esc_html( eqs__( '← All classes' ) ) . '</a> · <a href="' . esc_url( EQS_Front::page_url( array( 'view' => 'group', 'id' => $id ) ) ) . '" target="_blank">' . esc_html( eqs__( 'Results' ) ) . '</a> · <a href="' . esc_url( EQS_Front::page_url( array( 'view' => 'attendance', 'group' => $id ) ) ) . '" target="_blank">' . esc_html( eqs__( 'Attendance' ) ) . '</a></p>'; // phpcs:ignore

        echo '<h2>' . esc_html( eqs__( 'Subjects' ) ) . '</h2><p class="description">' . esc_html( eqs__( 'A subject is a Tutor LMS course taught to this group by a teacher. Subjects of a class also count for its sections. Students of the group are enrolled in the course.' ) ) . '</p>';
        echo '<table class="widefat striped"><thead><tr><th>' . esc_html( eqs__( 'Subject' ) ) . '</th><th>' . esc_html( eqs__( 'Course' ) ) . '</th><th>' . esc_html( eqs__( 'Teacher' ) ) . '</th><th></th></tr></thead><tbody>';
        foreach ( EQS_Model::subjects( $id ) as $s ) {
            $own = (int) $s->group_id === (int) $id;
            echo '<tr><td>' . esc_html( EQS_Model::subject_name( $s ) ) . ( $own ? '' : ' <em>' . esc_html( eqs__( '(from the class)' ) ) . '</em>' ) . '</td><td>' . esc_html( get_the_title( $s->course_id ) ) . '</td>' .
                '<td>' . esc_html( $s->teacher_id ? get_the_author_meta( 'display_name', $s->teacher_id ) : '—' ) . '</td><td>' .
                ( $own ? self::form( 'delete_subject', array( 'id' => $s->id, 'group' => $id ), eqs__( 'Remove' ), 'link-delete' ) : '' ) . '</td></tr>'; // phpcs:ignore
        }
        echo '</tbody></table>' . self::open( 'add_subject', array( 'group' => $id ) ) . '<p><select name="course_id"><option value="0">' . esc_html( eqs__( 'Course…' ) ) . '</option>'; // phpcs:ignore
        foreach ( get_posts( array( 'post_type' => function_exists( 'tutor' ) ? tutor()->course_post_type : 'courses', 'post_status' => 'publish', 'numberposts' => -1, 'orderby' => 'title', 'order' => 'ASC' ) ) as $c ) {
            echo '<option value="' . (int) $c->ID . '">' . esc_html( $c->post_title ) . '</option>';
        }
        echo '</select> ' . esc_html( eqs__( 'Teacher' ) ) . ' ' . self::select_users( 'teacher_id', self::teachers() ) . ' <input name="name" placeholder="' . esc_attr( eqs__( 'Subject name (optional)' ) ) . '"> '; // phpcs:ignore
        submit_button( eqs__( 'Add subject' ), 'secondary', 'submit', false );
        echo '</p></form>';

        echo '<h2>' . esc_html( eqs__( 'Students' ) ) . '</h2><table class="widefat striped"><thead><tr><th>' . esc_html( eqs__( 'Roll' ) ) . '</th><th>' . esc_html( eqs__( 'Name' ) ) . '</th><th>' . esc_html( eqs__( 'Email' ) ) . '</th><th>' . esc_html( eqs__( 'Group' ) ) . '</th><th></th></tr></thead><tbody>';
        foreach ( EQS_Model::students( $id ) as $st ) {
            echo '<tr><td>' . esc_html( $st->roll_no ) . '</td><td>' . esc_html( $st->display_name ) . '</td><td>' . esc_html( $st->user_email ) . '</td>' .
                '<td>' . esc_html( EQS_Model::label( EQS_Model::group( $st->group_id ) ) ) . '</td><td>' .
                self::form( 'remove_student', array( 'member_group' => $st->group_id, 'user' => $st->user_id, 'group' => $id ), eqs__( 'Remove' ), 'link-delete' ) . '</td></tr>'; // phpcs:ignore
        }
        echo '</tbody></table>' . self::open( 'add_students', array( 'group' => $id ) ); // phpcs:ignore
        echo '<p><label>' . esc_html( eqs__( 'Add students (one per line:' ) ) . ' <code>' . esc_html( eqs__( 'email or username, roll no' ) ) . '</code>)<br><textarea name="students" rows="5" cols="60" placeholder="' . esc_attr( eqs__( 'ali@example.com, 1&#10;sara, 2' ) ) . '"></textarea></label></p>';
        submit_button( eqs__( 'Add students' ), 'secondary' );
        echo '</form>';
        self::group_form( $g, EQS_Model::groups() );
        echo '</div>';
    }

    public static function page_parents() {
        echo '<div class="wrap"><h1>' . esc_html( eqs__( 'Parents' ) ) . '</h1>' . self::notice(); // phpcs:ignore
        echo '<p>' . esc_html( eqs__( 'A parent sees only the children linked here: progress, results, attendance and report cards (on the School page and in the app).' ) ) . '</p>';
        echo '<table class="widefat striped"><thead><tr><th>' . esc_html( eqs__( 'Parent' ) ) . '</th><th>' . esc_html( eqs__( 'Email' ) ) . '</th><th>' . esc_html( eqs__( 'Child' ) ) . '</th><th>' . esc_html( eqs__( 'Relation' ) ) . '</th><th></th></tr></thead><tbody>';
        foreach ( EQS_Model::parent_links() as $l ) {
            echo '<tr><td>' . esc_html( $l->parent_name ) . '</td><td>' . esc_html( $l->parent_email ) . '</td><td>' . esc_html( $l->student_name ) . '</td><td>' . esc_html( $l->relation ) . '</td>' .
                '<td>' . self::form( 'unlink_parent', array( 'parent' => $l->parent_id, 'student' => $l->student_id ), eqs__( 'Unlink' ), 'link-delete' ) . '</td></tr>'; // phpcs:ignore
        }
        echo '</tbody></table><h2>' . esc_html( eqs__( 'Add a parent or link more children' ) ) . '</h2>' . self::open( 'create_parent' ); // phpcs:ignore
        echo '<table class="form-table"><tr><th>' . esc_html( eqs__( 'Parent name' ) ) . '</th><td><input name="name" class="regular-text"></td></tr>' .
            '<tr><th>' . esc_html( eqs__( 'Parent email' ) ) . '</th><td><input type="email" name="email" class="regular-text" required><p class="description">' . esc_html( eqs__( 'If an account with this email exists it is used; otherwise a Parent account is created.' ) ) . '</p></td></tr>' .
            '<tr><th>' . esc_html( eqs__( 'Children' ) ) . '</th><td><textarea name="children" rows="3" cols="50" placeholder="' . esc_attr( eqs__( 'student email or username, one per line' ) ) . '"></textarea></td></tr>' .
            '<tr><th>' . esc_html( eqs__( 'Relation' ) ) . '</th><td><input name="relation" placeholder="' . esc_attr( eqs__( 'Father, Mother, Guardian' ) ) . '"></td></tr>' .
            '<tr><th></th><td><label><input type="checkbox" name="notify" value="1" checked> ' . esc_html( eqs__( 'E-mail the parent a link to set their password' ) ) . '</label></td></tr></table>';
        submit_button( eqs__( 'Save parent' ) );
        echo '</form></div>';
    }

    public static function page_terms() {
        $w = EQS_Stats::weights();
        echo '<div class="wrap"><h1>' . esc_html( eqs__( 'Report cards' ) ) . '</h1>' . self::notice(); // phpcs:ignore
        echo '<p>' . esc_html( eqs__( 'A report card shows each subject\'s results in the term (quiz attempts finished and assignments graded in the term), attendance in the term and the teacher\'s remark.' ) ) . '</p>';
        echo '<h2>' . esc_html( eqs__( 'Terms' ) ) . '</h2><table class="widefat striped"><thead><tr><th>' . esc_html( eqs__( 'Term' ) ) . '</th><th>' . esc_html( eqs__( 'From' ) ) . '</th><th>' . esc_html( eqs__( 'To' ) ) . '</th><th>' . esc_html( eqs__( 'Session' ) ) . '</th><th></th></tr></thead><tbody>';
        foreach ( EQS_Reports::terms() as $t ) {
            echo '<tr><td>' . esc_html( $t->name ) . '</td><td>' . esc_html( $t->start_date ) . '</td><td>' . esc_html( $t->end_date ) . '</td><td>' . esc_html( $t->session ) . '</td>' .
                '<td>' . self::form( 'delete_term', array( 'id' => $t->id ), eqs__( 'Delete' ), 'link-delete', eqs__( 'Delete this term and its remarks?' ) ) . '</td></tr>'; // phpcs:ignore
        }
        echo '</tbody></table>' . self::open( 'add_term' ) . '<p><input name="name" placeholder="' . esc_attr( eqs__( 'Term 1' ) ) . '" required> <input type="date" name="start" required> <input type="date" name="end" required> <input name="session" placeholder="2026-27"> '; // phpcs:ignore
        submit_button( eqs__( 'Add term' ), 'secondary', 'submit', false );
        echo '</p></form><h2>' . esc_html( eqs__( 'Grading' ) ) . '</h2>' . self::open( 'grading' ); // phpcs:ignore
        echo '<table class="form-table"><tr><th>' . esc_html( eqs__( 'Weights' ) ) . '</th><td>' . esc_html( eqs__( 'Quizzes' ) ) . ' <input type="number" name="w_quiz" class="small-text" value="' . (int) $w['quiz'] . '"> · ' . esc_html( eqs__( 'Assignments' ) ) . ' <input type="number" name="w_assignment" class="small-text" value="' . (int) $w['assignment'] . '"><p class="description">' . esc_html( eqs__( 'When a subject has only quizzes (or only assignments), that part counts 100%.' ) ) . '</p></td></tr>' .
            '<tr><th>' . esc_html( eqs__( 'Grade scale' ) ) . '</th><td><textarea name="scale" rows="7" cols="20">' . esc_textarea( implode( "\n", array_map( function ( $r ) {
                return rtrim( rtrim( number_format( $r[0], 1, '.', '' ), '0' ), '.' ) . ' ' . $r[1];
            }, EQS_Stats::scale() ) ) ) . '</textarea><p class="description">' . esc_html( eqs__( 'One per line:' ) ) . ' <code>' . esc_html( eqs__( 'minimum percent grade' ) ) . '</code></p></td></tr></table>';
        submit_button( eqs__( 'Save grading' ) );
        echo '</form></div>';
    }
}
