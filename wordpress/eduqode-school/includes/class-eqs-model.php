<?php
/**
 * Groups, subjects, students and parents, and who may see what:
 *   principal  administrators (manage_options) or users with the "eqs_principal" capability: everything
 *   teacher    groups where they are class teacher or teach a subject (a subject given to a class
 *              covers all its sections)
 *   parent     only the students the admin linked to them
 *   student    only themselves
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EQS_Model {

    const KINDS = array( 'class' => 'Class', 'section' => 'Section', 'batch' => 'Batch', 'team' => 'Team' );

    /** Translated name of a group type. */
    public static function kind_label( $kind ) {
        $labels = array( 'class' => eqs__( 'Class' ), 'section' => eqs__( 'Section' ), 'batch' => eqs__( 'Batch' ), 'team' => eqs__( 'Team / department' ) );
        return $labels[ $kind ] ?? $kind;
    }

    // ------------------------------------------------------------ groups

    public static function group( $id ) {
        global $wpdb;
        return $wpdb->get_row( $wpdb->prepare( 'SELECT * FROM ' . EQS_DB::t( 'groups' ) . ' WHERE id = %d', $id ) );
    }

    public static function groups() {
        global $wpdb;
        return $wpdb->get_results( 'SELECT * FROM ' . EQS_DB::t( 'groups' ) . ' ORDER BY sort, name, id' );
    }

    /** "Class 7 – A" for a section, the name for anything else. */
    public static function label( $g ) {
        if ( ! $g ) {
            return '';
        }
        if ( 'section' === $g->kind && $g->parent_id && ( $p = self::group( $g->parent_id ) ) ) {
            return $p->name . ' – ' . $g->name;
        }
        return $g->name;
    }

    public static function save_group( $data ) {
        global $wpdb;
        $kind = isset( self::KINDS[ $data['kind'] ?? '' ] ) ? $data['kind'] : 'class';
        $row  = array(
            'kind'             => $kind,
            'name'             => sanitize_text_field( $data['name'] ?? '' ),
            'parent_id'        => 'section' === $kind ? absint( $data['parent_id'] ?? 0 ) : 0,
            'session'          => sanitize_text_field( $data['session'] ?? '' ),
            'class_teacher_id' => absint( $data['class_teacher_id'] ?? 0 ),
            'sort'             => (int) ( $data['sort'] ?? 0 ),
        );
        if ( '' === $row['name'] ) {
            return 0;
        }
        if ( ! empty( $data['id'] ) ) {
            $wpdb->update( EQS_DB::t( 'groups' ), $row, array( 'id' => absint( $data['id'] ) ) );
            return absint( $data['id'] );
        }
        $wpdb->insert( EQS_DB::t( 'groups' ), $row );
        return (int) $wpdb->insert_id;
    }

    public static function delete_group( $id ) {
        global $wpdb;
        $id = absint( $id );
        foreach ( array( 'subjects', 'members', 'attendance', 'remarks' ) as $t ) {
            $wpdb->delete( EQS_DB::t( $t ), array( 'group_id' => $id ) );
        }
        $wpdb->update( EQS_DB::t( 'groups' ), array( 'parent_id' => 0, 'kind' => 'class' ), array( 'parent_id' => $id ) );
        $wpdb->delete( EQS_DB::t( 'groups' ), array( 'id' => $id ) );
    }

    public static function sections( $class_id ) {
        global $wpdb;
        return $wpdb->get_results( $wpdb->prepare( 'SELECT * FROM ' . EQS_DB::t( 'groups' ) . ' WHERE parent_id = %d ORDER BY sort, name', $class_id ) );
    }

    // ------------------------------------------------------------ subjects

    /** Subjects of a group, including those given to its class (for a section). */
    public static function subjects( $group_id ) {
        global $wpdb;
        $g   = self::group( $group_id );
        $ids = array( (int) $group_id );
        if ( $g && $g->parent_id ) {
            $ids[] = (int) $g->parent_id;
        }
        return $wpdb->get_results( 'SELECT * FROM ' . EQS_DB::t( 'subjects' ) . ' WHERE group_id IN (' . implode( ',', $ids ) . ') ORDER BY name, id' );
    }

    public static function subject( $id ) {
        global $wpdb;
        return $wpdb->get_row( $wpdb->prepare( 'SELECT * FROM ' . EQS_DB::t( 'subjects' ) . ' WHERE id = %d', $id ) );
    }

    public static function subject_name( $s ) {
        return $s->name !== '' ? $s->name : get_the_title( $s->course_id );
    }

    public static function add_subject( $group_id, $course_id, $teacher_id, $name ) {
        global $wpdb;
        if ( ! self::group( $group_id ) || ( function_exists( 'tutor' ) && get_post_type( $course_id ) !== tutor()->course_post_type ) ) {
            return 0;
        }
        $wpdb->insert( EQS_DB::t( 'subjects' ), array(
            'group_id' => absint( $group_id ), 'course_id' => absint( $course_id ), 'teacher_id' => absint( $teacher_id ),
            'name' => sanitize_text_field( $name ),
        ) );
        return (int) $wpdb->insert_id;
    }

    public static function delete_subject( $id ) {
        global $wpdb;
        $wpdb->delete( EQS_DB::t( 'subjects' ), array( 'id' => absint( $id ) ) );
    }

    // ------------------------------------------------------------ students

    /** Students of a group (a class also lists the students of its sections). */
    public static function students( $group_id ) {
        global $wpdb;
        $ids = array( (int) $group_id );
        foreach ( self::sections( $group_id ) as $s ) {
            $ids[] = (int) $s->id;
        }
        $rows = $wpdb->get_results(
            'SELECT m.user_id, m.roll_no, m.group_id, u.display_name, u.user_email FROM ' . EQS_DB::t( 'members' ) . " m
             JOIN {$wpdb->users} u ON u.ID = m.user_id WHERE m.group_id IN (" . implode( ',', $ids ) . ')'
        );
        usort( $rows, function ( $a, $b ) {
            return strnatcasecmp( $a->roll_no . ' ' . $a->display_name, $b->roll_no . ' ' . $b->display_name );
        } );
        return $rows;
    }

    public static function add_student( $group_id, $user_id, $roll_no = '' ) {
        global $wpdb;
        if ( ! self::group( $group_id ) || ! get_userdata( $user_id ) ) {
            return false;
        }
        $wpdb->replace( EQS_DB::t( 'members' ), array( 'group_id' => absint( $group_id ), 'user_id' => absint( $user_id ), 'roll_no' => sanitize_text_field( $roll_no ) ) );
        // Students get the group's courses in Tutor LMS (if they were not enrolled yet).
        if ( eqs_tutor_ready() ) {
            foreach ( self::subjects( $group_id ) as $s ) {
                if ( ! tutor_utils()->is_enrolled( $s->course_id, $user_id ) ) {
                    tutor_utils()->do_enroll( $s->course_id, 0, $user_id );
                }
            }
        }
        return true;
    }

    public static function remove_student( $group_id, $user_id ) {
        global $wpdb;
        $wpdb->delete( EQS_DB::t( 'members' ), array( 'group_id' => absint( $group_id ), 'user_id' => absint( $user_id ) ) );
    }

    /** Groups a student belongs to. */
    public static function student_groups( $user_id ) {
        global $wpdb;
        return $wpdb->get_results( $wpdb->prepare(
            'SELECT g.*, m.roll_no FROM ' . EQS_DB::t( 'members' ) . ' m JOIN ' . EQS_DB::t( 'groups' ) . ' g ON g.id = m.group_id WHERE m.user_id = %d ORDER BY g.sort, g.name',
            $user_id
        ) );
    }

    // ------------------------------------------------------------ parents

    public static function link_parent( $parent_id, $student_id, $relation = '' ) {
        global $wpdb;
        if ( ! get_userdata( $parent_id ) || ! get_userdata( $student_id ) || (int) $parent_id === (int) $student_id ) {
            return false;
        }
        $wpdb->replace( EQS_DB::t( 'parents' ), array( 'parent_id' => absint( $parent_id ), 'student_id' => absint( $student_id ), 'relation' => sanitize_text_field( $relation ) ) );
        $u = new WP_User( $parent_id );
        if ( ! $u->has_cap( 'edit_posts' ) && ! in_array( 'eqs_parent', (array) $u->roles, true ) ) {
            $u->add_role( 'eqs_parent' );
        }
        return true;
    }

    public static function unlink_parent( $parent_id, $student_id ) {
        global $wpdb;
        $wpdb->delete( EQS_DB::t( 'parents' ), array( 'parent_id' => absint( $parent_id ), 'student_id' => absint( $student_id ) ) );
    }

    public static function children( $parent_id ) {
        global $wpdb;
        return $wpdb->get_results( $wpdb->prepare(
            'SELECT p.student_id, p.relation, u.display_name FROM ' . EQS_DB::t( 'parents' ) . " p JOIN {$wpdb->users} u ON u.ID = p.student_id WHERE p.parent_id = %d ORDER BY u.display_name",
            $parent_id
        ) );
    }

    public static function parent_links() {
        global $wpdb;
        return $wpdb->get_results(
            'SELECT p.*, pu.display_name AS parent_name, pu.user_email AS parent_email, su.display_name AS student_name FROM ' . EQS_DB::t( 'parents' ) . " p
             JOIN {$wpdb->users} pu ON pu.ID = p.parent_id JOIN {$wpdb->users} su ON su.ID = p.student_id ORDER BY pu.display_name"
        );
    }

    // ------------------------------------------------------------ access

    public static function is_principal( $user_id = 0 ) {
        $user_id = $user_id ?: get_current_user_id();
        return $user_id && ( user_can( $user_id, 'manage_options' ) || user_can( $user_id, 'eqs_principal' ) );
    }

    /** Groups this teacher may see (class teacher, or a subject teacher of the group or of its class). */
    public static function teacher_groups( $user_id = 0 ) {
        global $wpdb;
        $user_id = $user_id ?: get_current_user_id();
        if ( ! $user_id ) {
            return array();
        }
        $ids = array_map( 'intval', $wpdb->get_col( $wpdb->prepare(
            'SELECT DISTINCT group_id FROM ' . EQS_DB::t( 'subjects' ) . ' WHERE teacher_id = %d', $user_id
        ) ) );
        $ids = array_merge( $ids, array_map( 'intval', $wpdb->get_col( $wpdb->prepare(
            'SELECT id FROM ' . EQS_DB::t( 'groups' ) . ' WHERE class_teacher_id = %d', $user_id
        ) ) ) );
        foreach ( $ids as $id ) {
            foreach ( self::sections( $id ) as $s ) {
                $ids[] = (int) $s->id;
            }
        }
        $ids = array_values( array_unique( $ids ) );
        return array_values( array_filter( array_map( array( __CLASS__, 'group' ), $ids ) ) );
    }

    public static function can_see_group( $group_id, $user_id = 0 ) {
        if ( self::is_principal( $user_id ) ) {
            return true;
        }
        return in_array( (int) $group_id, array_map( function ( $g ) {
            return (int) $g->id;
        }, self::teacher_groups( $user_id ) ), true );
    }

    /** Teacher of the subject, class teacher, or principal. */
    public static function can_teach_subject( $subject, $user_id = 0 ) {
        $user_id = $user_id ?: get_current_user_id();
        if ( ! $subject ) {
            return false;
        }
        if ( self::is_principal( $user_id ) || (int) $subject->teacher_id === (int) $user_id ) {
            return true;
        }
        $g = self::group( $subject->group_id );
        return $g && (int) $g->class_teacher_id === (int) $user_id;
    }

    /** Marking attendance: class teacher (of the group or its class), subject teachers, principal. */
    public static function can_mark_attendance( $group_id, $user_id = 0 ) {
        return self::can_see_group( $group_id, $user_id );
    }

    /** Seeing one student: principal, their teachers, their parents, the student. */
    public static function can_see_student( $student_id, $user_id = 0 ) {
        global $wpdb;
        $user_id = $user_id ?: get_current_user_id();
        if ( ! $user_id ) {
            return false;
        }
        if ( (int) $student_id === (int) $user_id || self::is_principal( $user_id ) ) {
            return true;
        }
        if ( $wpdb->get_var( $wpdb->prepare( 'SELECT 1 FROM ' . EQS_DB::t( 'parents' ) . ' WHERE parent_id = %d AND student_id = %d', $user_id, $student_id ) ) ) {
            return true;
        }
        foreach ( self::student_groups( $student_id ) as $g ) {
            if ( self::can_see_group( $g->id, $user_id ) ) {
                return true;
            }
        }
        return false;
    }

    /** What the School page shows first for this user. */
    public static function roles( $user_id = 0 ) {
        $user_id = $user_id ?: get_current_user_id();
        $r       = array();
        if ( self::is_principal( $user_id ) ) {
            $r[] = 'principal';
        }
        if ( self::teacher_groups( $user_id ) ) {
            $r[] = 'teacher';
        }
        if ( self::children( $user_id ) ) {
            $r[] = 'parent';
        }
        if ( self::student_groups( $user_id ) ) {
            $r[] = 'student';
        }
        return $r;
    }
}
