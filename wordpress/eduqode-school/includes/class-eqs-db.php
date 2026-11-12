<?php
/**
 * Tables, the Parent role and the School page.
 *
 *   eqs_groups      class | section (parent = class) | batch
 *   eqs_subjects    a Tutor LMS course taught to a group by a teacher
 *   eqs_members     students of a group (roll number)
 *   eqs_parents     parent account ↔ student account (linked by the admin)
 *   eqs_attendance  one row per group, day and student
 *   eqs_terms       report card terms (date ranges)
 *   eqs_remarks     teacher's remark per term and student
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EQS_DB {

    const DB_VERSION = 1;

    public static function t( $name ) {
        global $wpdb;
        return $wpdb->prefix . 'eqs_' . $name;
    }

    public static function activate() {
        self::create_tables();
        add_role( 'eqs_parent', 'Parent', array( 'read' => true ) );
        if ( ! get_option( 'eqs_page_id' ) || 'publish' !== get_post_status( (int) get_option( 'eqs_page_id' ) ) ) {
            $id = wp_insert_post( array(
                'post_type'    => 'page',
                'post_status'  => 'publish',
                'post_title'   => 'School',
                'post_name'    => 'school',
                'post_content' => '[eduqode_school]',
            ) );
            update_option( 'eqs_page_id', (int) $id );
        }
        update_option( 'eqs_db_version', self::DB_VERSION );
    }

    public static function maybe_upgrade() {
        if ( (int) get_option( 'eqs_db_version' ) < self::DB_VERSION ) {
            self::activate();
        }
    }

    public static function create_tables() {
        global $wpdb;
        $c = $wpdb->get_charset_collate();
        require_once ABSPATH . 'wp-admin/includes/upgrade.php';
        dbDelta( 'CREATE TABLE ' . self::t( 'groups' ) . " (
            id BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT,
            kind VARCHAR(20) NOT NULL DEFAULT 'class',
            name VARCHAR(190) NOT NULL,
            parent_id BIGINT(20) UNSIGNED NOT NULL DEFAULT 0,
            session VARCHAR(40) NOT NULL DEFAULT '',
            class_teacher_id BIGINT(20) UNSIGNED NOT NULL DEFAULT 0,
            sort INT NOT NULL DEFAULT 0,
            PRIMARY KEY  (id),
            KEY parent_id (parent_id)
        ) $c;" );
        dbDelta( 'CREATE TABLE ' . self::t( 'subjects' ) . " (
            id BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT,
            group_id BIGINT(20) UNSIGNED NOT NULL,
            course_id BIGINT(20) UNSIGNED NOT NULL,
            teacher_id BIGINT(20) UNSIGNED NOT NULL DEFAULT 0,
            name VARCHAR(190) NOT NULL DEFAULT '',
            PRIMARY KEY  (id),
            KEY group_id (group_id),
            KEY teacher_id (teacher_id)
        ) $c;" );
        dbDelta( 'CREATE TABLE ' . self::t( 'members' ) . " (
            group_id BIGINT(20) UNSIGNED NOT NULL,
            user_id BIGINT(20) UNSIGNED NOT NULL,
            roll_no VARCHAR(40) NOT NULL DEFAULT '',
            PRIMARY KEY  (group_id,user_id),
            KEY user_id (user_id)
        ) $c;" );
        dbDelta( 'CREATE TABLE ' . self::t( 'parents' ) . " (
            parent_id BIGINT(20) UNSIGNED NOT NULL,
            student_id BIGINT(20) UNSIGNED NOT NULL,
            relation VARCHAR(40) NOT NULL DEFAULT '',
            PRIMARY KEY  (parent_id,student_id),
            KEY student_id (student_id)
        ) $c;" );
        dbDelta( 'CREATE TABLE ' . self::t( 'attendance' ) . " (
            group_id BIGINT(20) UNSIGNED NOT NULL,
            att_date DATE NOT NULL,
            student_id BIGINT(20) UNSIGNED NOT NULL,
            status VARCHAR(1) NOT NULL DEFAULT 'P',
            note VARCHAR(190) NOT NULL DEFAULT '',
            marked_by BIGINT(20) UNSIGNED NOT NULL DEFAULT 0,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY  (group_id,att_date,student_id),
            KEY student_id (student_id)
        ) $c;" );
        dbDelta( 'CREATE TABLE ' . self::t( 'terms' ) . " (
            id BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT,
            name VARCHAR(190) NOT NULL,
            start_date DATE NOT NULL,
            end_date DATE NOT NULL,
            session VARCHAR(40) NOT NULL DEFAULT '',
            PRIMARY KEY  (id)
        ) $c;" );
        dbDelta( 'CREATE TABLE ' . self::t( 'remarks' ) . " (
            term_id BIGINT(20) UNSIGNED NOT NULL,
            group_id BIGINT(20) UNSIGNED NOT NULL,
            student_id BIGINT(20) UNSIGNED NOT NULL,
            remark TEXT NOT NULL,
            updated_by BIGINT(20) UNSIGNED NOT NULL DEFAULT 0,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY  (term_id,group_id,student_id)
        ) $c;" );
    }
}
