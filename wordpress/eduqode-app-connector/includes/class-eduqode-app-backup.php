<?php
/**
 * Backup & restore add-on for Tutor LMS Pro's own "Tools → Export / Import".
 *
 * Tutor LMS Pro exports courses, lessons, quizzes, settings, media and (optionally) students'
 * enrollments and progress. It does not export what lives outside Tutor:
 *   - H5P exercises (the H5P plugin's tables and wp-content/uploads/h5p),
 *   - students' H5P results (Tutor LMS Pro H5P tables, H5P plugin results, "H5P finished" marks),
 *   - this plugin's data (offline video links, app settings, video positions and last lesson).
 * This add-on exports exactly that into one zip, and restores it on another Tutor LMS site
 * AFTER Tutor LMS Pro's import. New ids are found from the course structure (course slug,
 * topic, item title and order); students are matched by email.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Backup {

    const FORMAT       = 1;
    const IMPORTED_OPT = 'eduqode_app_backup_imported';   // backup id => time (student data restored)
    const H5P_MAP_OPT  = 'eduqode_app_backup_h5p_map';    // "backup id:old content id" => new content id
    const SETTINGS     = array(
        'eduqode_app_google_login', 'eduqode_app_blocked_paths', 'eduqode_app_blocked_query', 'eduqode_app_css',
        'eduqode_app_offline_enabled', 'eduqode_app_protect_tutor_js',
    );

    // ------------------------------------------------------------------ structure

    /** Course outline used to find the same items on another site (all statuses). */
    public static function outline( $course_id ) {
        global $wpdb;
        $course = get_post( $course_id );
        $out    = array(
            'id'     => (int) $course_id,
            'slug'   => (string) $course->post_name,
            'title'  => (string) $course->post_title,
            'topics' => array(),
        );
        foreach ( self::children( $course_id, 'topics' ) as $topic ) {
            $items = self::children( $topic->ID, null );
            $list  = array();
            foreach ( $items as $item ) {
                $entry = array( 'id' => (int) $item->ID, 'type' => $item->post_type, 'title' => (string) $item->post_title );
                if ( 'tutor_quiz' === $item->post_type ) {
                    $entry['questions'] = array_map(
                        function ( $q ) {
                            return array( 'id' => (int) $q->question_id, 'title' => (string) $q->question_title, 'type' => (string) $q->question_type );
                        },
                        $wpdb->get_results( $wpdb->prepare(
                            "SELECT question_id, question_title, question_type FROM {$wpdb->prefix}tutor_quiz_questions WHERE quiz_id = %d ORDER BY question_order, question_id",
                            $item->ID
                        ) )
                    );
                }
                $list[] = $entry;
            }
            $out['topics'][] = array( 'id' => (int) $topic->ID, 'title' => (string) $topic->post_title, 'items' => $list );
        }
        return $out;
    }

    /** Child posts in course order (any status except trash/revisions). */
    private static function children( $parent, $type ) {
        global $wpdb;
        $sql = "SELECT ID, post_type, post_title FROM {$wpdb->posts} WHERE post_parent = %d
                AND post_status NOT IN ('trash', 'auto-draft', 'inherit') AND post_type NOT IN ('revision', 'attachment')";
        $args = array( (int) $parent );
        if ( $type ) {
            $sql   .= ' AND post_type = %s';
            $args[] = $type;
        }
        return $wpdb->get_results( $wpdb->prepare( $sql . ' ORDER BY menu_order, ID', $args ) );
    }

    /** Pairs old and new entries: same title in order first, then whatever is left by position. */
    private static function pair( $old, $new, $key_fn ) {
        $map  = array();
        $used = array();
        foreach ( $old as $i => $o ) {
            foreach ( $new as $j => $n ) {
                if ( ! isset( $used[ $j ] ) && $key_fn( $o ) === $key_fn( $n ) ) {
                    $map[ $i ] = $j;
                    $used[ $j ] = true;
                    break;
                }
            }
        }
        foreach ( $old as $i => $o ) {
            if ( isset( $map[ $i ] ) ) {
                continue;
            }
            if ( isset( $new[ $i ] ) && ! isset( $used[ $i ] ) ) {
                $map[ $i ] = $i;
                $used[ $i ] = true;
            }
        }
        return $map;
    }

    /** Finds the course on this site (newest with the same slug, else the newest with the same title). */
    private static function find_course( $outline ) {
        global $wpdb;
        $type = function_exists( 'tutor' ) ? tutor()->course_post_type : 'courses';
        $id   = 0;
        if ( '' !== $outline['slug'] ) {
            // Same slug, or the slug WordPress gives a copy ("my-course-2").
            $rows = $wpdb->get_results( $wpdb->prepare(
                "SELECT ID, post_name FROM {$wpdb->posts} WHERE post_type = %s AND post_status <> 'trash' AND (post_name = %s OR post_name LIKE %s)",
                $type, $outline['slug'], $wpdb->esc_like( $outline['slug'] ) . '-%'
            ) );
            foreach ( $rows as $r ) {
                if ( preg_match( '/^' . preg_quote( $outline['slug'], '/' ) . '(-\d+)?$/', $r->post_name ) ) {
                    $id = max( $id, (int) $r->ID );
                }
            }
        }
        if ( ! $id ) {
            $id = (int) $wpdb->get_var( $wpdb->prepare(
                "SELECT ID FROM {$wpdb->posts} WHERE post_type = %s AND post_status <> 'trash' AND post_title = %s ORDER BY ID DESC LIMIT 1",
                $type, $outline['title']
            ) );
        }
        return $id;
    }

    /** old id => new id for the course, topics, lessons, quizzes, assignments and questions. */
    public static function map_ids( $old_outline ) {
        $map = array( 'posts' => array(), 'questions' => array() );
        $new_id = self::find_course( $old_outline );
        if ( ! $new_id ) {
            return null;
        }
        $map['posts'][ $old_outline['id'] ] = $new_id;
        $new = self::outline( $new_id );
        $by_title = function ( $x ) {
            return trim( strtolower( $x['title'] ) );
        };
        foreach ( self::pair( $old_outline['topics'], $new['topics'], $by_title ) as $ti => $tj ) {
            $ot = $old_outline['topics'][ $ti ];
            $nt = $new['topics'][ $tj ];
            $map['posts'][ $ot['id'] ] = $nt['id'];
            $by_type_title = function ( $x ) {
                return $x['type'] . '|' . trim( strtolower( $x['title'] ) );
            };
            foreach ( self::pair( $ot['items'], $nt['items'], $by_type_title ) as $ii => $ij ) {
                $oi = $ot['items'][ $ii ];
                $ni = $nt['items'][ $ij ];
                if ( $oi['type'] !== $ni['type'] ) {
                    continue;
                }
                $map['posts'][ $oi['id'] ] = $ni['id'];
                if ( ! empty( $oi['questions'] ) && ! empty( $ni['questions'] ) ) {
                    foreach ( self::pair( $oi['questions'], $ni['questions'], $by_type_title ) as $qi => $qj ) {
                        $map['questions'][ $oi['questions'][ $qi ]['id'] ] = $ni['questions'][ $qj ]['id'];
                    }
                }
            }
        }
        return $map;
    }

    // ------------------------------------------------------------------ export

    private static function h5p_ids_in_course( $outline ) {
        global $wpdb;
        $ids = array();
        foreach ( $outline['topics'] as $t ) {
            foreach ( $t['items'] as $i ) {
                if ( 'tutor_quiz' === $i['type'] ) {
                    foreach ( $i['questions'] as $q ) {
                        if ( 'h5p' === $q['type'] ) {
                            $desc = (string) $wpdb->get_var( $wpdb->prepare( "SELECT question_description FROM {$wpdb->prefix}tutor_quiz_questions WHERE question_id = %d", $q['id'] ) );
                            $cid  = EduQode_App_H5P::question_content_id( (object) array( 'question_description' => $desc ) );
                            if ( $cid ) {
                                $ids[] = $cid;
                            }
                        }
                    }
                } else {
                    $ids = array_merge( $ids, EduQode_App_H5P::ids_in_text( get_post_field( 'post_content', $i['id'] ) ) );
                }
            }
        }
        return array_values( array_unique( array_map( 'intval', $ids ) ) );
    }

    /** Builds a real .h5p file with the H5P plugin's own exporter. Returns the file path or ''. */
    private static function h5p_file( $content_id ) {
        if ( ! EduQode_App_H5P::available() ) {
            return '';
        }
        $plugin = H5P_Plugin::get_instance();
        $core   = $plugin->get_h5p_instance( 'core' );
        $content = $core->loadContent( (int) $content_id );
        if ( ! is_array( $content ) ) {
            return '';
        }
        $up   = wp_upload_dir();
        $dir  = trailingslashit( $up['basedir'] ) . 'h5p/exports/';
        $find = function () use ( $dir, $content_id ) {
            $hits = glob( $dir . '*-' . (int) $content_id . '.h5p' );
            return $hits ? $hits[0] : '';
        };
        $file = $find();
        if ( ! $file ) {
            // Same as showing the content with "Allow download" on: the H5P plugin writes the export file.
            $core->exportEnabled = true;
            $content['filtered'] = '';
            $core->filterParameters( $content );
            $file = $find();
        }
        return $file && filesize( $file ) > 0 ? $file : '';
    }

    private static function rows( $sql ) {
        global $wpdb;
        return $wpdb->get_results( $sql, ARRAY_A ) ?: array();
    }

    private static function table_exists( $t ) {
        global $wpdb;
        return $wpdb->prefix . $t === $wpdb->get_var( $wpdb->prepare( 'SHOW TABLES LIKE %s', $wpdb->prefix . $t ) );
    }

    /** Students' data for these courses, with user ids replaced by emails. */
    private static function student_data( $outlines, $h5p_ids ) {
        global $wpdb;
        $lessons = array();
        $quizzes = array();
        $courses = array();
        foreach ( $outlines as $o ) {
            $courses[] = $o['id'];
            foreach ( $o['topics'] as $t ) {
                foreach ( $t['items'] as $i ) {
                    if ( 'tutor_quiz' === $i['type'] ) {
                        $quizzes[] = $i['id'];
                    } else {
                        $lessons[] = $i['id'];
                    }
                }
            }
        }
        $in = function ( $ids ) {
            return $ids ? implode( ',', array_map( 'intval', $ids ) ) : '0';
        };
        $users = array();
        $email = function ( $uid ) use ( &$users ) {
            $uid = (int) $uid;
            if ( ! isset( $users[ $uid ] ) ) {
                $u = get_userdata( $uid );
                $users[ $uid ] = $u ? $u->user_email : '';
            }
            return $users[ $uid ];
        };
        $data = array( 'meta' => array(), 'h5p_results' => array(), 'statements' => array(), 'lesson_statements' => array(), 'quiz_statements' => array(), 'quiz_results' => array() );

        // "H5P finished" marks, offline video positions and last lesson.
        $p = $wpdb->prefix;
        foreach ( self::rows( "SELECT user_id, meta_key, meta_value FROM {$wpdb->usermeta} WHERE meta_key LIKE '\\_tutor\\_h5p\\_lesson\\_completed\\_%'" ) as $r ) {
            $lid = (int) substr( $r['meta_key'], strlen( '_tutor_h5p_lesson_completed_' ) );
            if ( in_array( $lid, $lessons, true ) && $email( $r['user_id'] ) ) {
                $data['meta'][] = array( 'email' => $email( $r['user_id'] ), 'type' => 'h5p_finished', 'lesson' => $lid );
            }
        }
        foreach ( array( EduQode_App_Sync::POSITIONS_META => $lessons, EduQode_App_Sync::LAST_META => $courses ) as $key => $allowed ) {
            foreach ( self::rows( $wpdb->prepare( "SELECT user_id, meta_value FROM {$wpdb->usermeta} WHERE meta_key = %s", $key ) ) as $r ) {
                $value = maybe_unserialize( $r['meta_value'] );
                if ( ! is_array( $value ) || ! $email( $r['user_id'] ) ) {
                    continue;
                }
                $value = array_intersect_key( $value, array_flip( $allowed ) );
                if ( $value ) {
                    $data['meta'][] = array( 'email' => $email( $r['user_id'] ), 'type' => $key, 'value' => $value );
                }
            }
        }
        if ( $h5p_ids && self::table_exists( 'h5p_results' ) ) {
            foreach ( self::rows( "SELECT * FROM {$p}h5p_results WHERE content_id IN ({$in($h5p_ids)})" ) as $r ) {
                $r['email'] = $email( $r['user_id'] );
                unset( $r['id'], $r['user_id'] );
                $data['h5p_results'][] = $r;
            }
        }
        if ( self::table_exists( 'tutor_h5p_statement' ) ) {
            $tables = array(
                'statements'        => "SELECT * FROM {$p}tutor_h5p_statement WHERE content_id IN ({$in($h5p_ids)})",
                'lesson_statements' => "SELECT * FROM {$p}tutor_h5p_lesson_statement WHERE lesson_id IN ({$in($lessons)})",
                'quiz_statements'   => "SELECT * FROM {$p}tutor_h5p_quiz_statement WHERE quiz_id IN ({$in($quizzes)})",
                'quiz_results'      => "SELECT r.*, a.attempt_started_at FROM {$p}tutor_h5p_quiz_result r LEFT JOIN {$p}tutor_quiz_attempts a ON a.attempt_id = r.attempt_id WHERE r.quiz_id IN ({$in($quizzes)})",
            );
            foreach ( $tables as $name => $sql ) {
                foreach ( self::rows( $sql ) as $r ) {
                    $r['email'] = $email( $r['user_id'] );
                    if ( isset( $r['instructor_id'] ) ) {
                        $r['instructor_email'] = $email( $r['instructor_id'] );
                    }
                    unset( $r['user_id'], $r['instructor_id'], $r['statement_id'] );
                    if ( '' !== $r['email'] ) {
                        $data[ $name ][] = $r;
                    }
                }
            }
        }
        return $data;
    }

    /**
     * Creates the backup zip.
     * @param int[] $course_ids  courses to include (empty = all courses)
     * @return string path of the zip (in the uploads temp folder; the caller deletes it)
     */
    public static function export( $course_ids, $with_students, $with_settings = true ) {
        if ( ! class_exists( 'ZipArchive' ) ) {
            throw new Exception( 'The PHP zip extension is needed to create a backup.' );
        }
        $type = function_exists( 'tutor' ) ? tutor()->course_post_type : 'courses';
        if ( ! $course_ids ) {
            $course_ids = get_posts( array( 'post_type' => $type, 'post_status' => 'any', 'numberposts' => -1, 'fields' => 'ids' ) );
        }
        $outlines = array();
        $h5p      = array();
        $videos   = array();
        foreach ( array_map( 'intval', (array) $course_ids ) as $cid ) {
            if ( get_post_type( $cid ) !== $type ) {
                continue;
            }
            $o          = self::outline( $cid );
            $outlines[] = $o;
            $h5p        = array_merge( $h5p, self::h5p_ids_in_course( $o ) );
            foreach ( $o['topics'] as $t ) {
                foreach ( $t['items'] as $i ) {
                    $v = (string) get_post_meta( $i['id'], EduQode_App_Courses::VIDEO_META, true );
                    if ( '' !== $v ) {
                        $videos[ $i['id'] ] = $v;
                    }
                }
            }
        }
        $h5p = array_values( array_unique( $h5p ) );

        $backup_id = wp_generate_uuid4();
        $tmp       = wp_tempnam( 'eduqode-backup' );
        $zip       = new ZipArchive();
        if ( true !== $zip->open( $tmp, ZipArchive::CREATE | ZipArchive::OVERWRITE ) ) {
            throw new Exception( 'Could not create the backup file.' );
        }
        $h5p_list = array();
        foreach ( $h5p as $cid ) {
            $file = self::h5p_file( $cid );
            $row  = $file ? $GLOBALS['wpdb']->get_row( $GLOBALS['wpdb']->prepare( "SELECT title FROM {$GLOBALS['wpdb']->prefix}h5p_contents WHERE id = %d", $cid ) ) : null;
            if ( $file ) {
                $zip->addFile( $file, 'h5p/' . $cid . '.h5p' );
                $h5p_list[] = array( 'id' => $cid, 'title' => $row ? (string) $row->title : '' );
            }
        }
        $settings = array();
        if ( $with_settings ) {
            foreach ( self::SETTINGS as $opt ) {
                $value = get_option( $opt, null );
                if ( null !== $value ) {
                    $settings[ $opt ] = $value;
                }
            }
        }
        $manifest = array(
            'format'      => self::FORMAT,
            'backup_id'   => $backup_id,
            'plugin'      => EDUQODE_APP_VERSION,
            'site'        => home_url(),
            'created_at'  => gmdate( 'c' ),
            'courses'     => $outlines,
            'h5p'         => $h5p_list,
            'video_links' => $videos,
            'settings'    => $settings,
            'students'    => $with_students ? self::student_data( $outlines, $h5p ) : null,
        );
        $zip->addFromString( 'manifest.json', wp_json_encode( $manifest ) );
        $zip->addFromString( 'README.txt', "EduQode App backup ($backup_id)\nRestore it on the new site AFTER importing Tutor LMS Pro's own export:\nSettings → EduQode App → Backup & restore.\n" );
        $zip->close();
        return $tmp;
    }

    // ------------------------------------------------------------------ import

    private static function import_h5p_file( $path, $title ) {
        $plugin = H5P_Plugin::get_instance();
        $iface  = $plugin->get_h5p_instance( 'interface' );
        $valid  = $plugin->get_h5p_instance( 'validator' );
        $target = $iface->getUploadedH5pPath();
        wp_mkdir_p( dirname( $target ) );
        copy( $path, $target );
        if ( ! $valid->isValidPackage( false, false ) ) {
            @unlink( $target );
            return 0;
        }
        $content = array( 'metadata' => array( 'title' => $title ?: 'H5P' ), 'disable' => 0 );
        $storage = $plugin->get_h5p_instance( 'storage' );
        $storage->savePackage( $content, null, false );
        return (int) $storage->contentId;
    }

    /** Re-maps the content ids and replaces the shortcodes. */
    private static function remap_h5p_text( $text, $h5p_map ) {
        return preg_replace_callback(
            '/\[h5p\s+id=(["\']?)(\d+)\1\s*\]/i',
            function ( $m ) use ( $h5p_map ) {
                $new = $h5p_map[ (int) $m[2] ] ?? 0;
                return $new ? '[h5p id="' . $new . '"]' : $m[0];
            },
            (string) $text
        );
    }

    /**
     * Restores a backup zip. Safe to run again: exercises are not imported twice and students'
     * results are restored only once per backup.
     * @return array report
     */
    public static function import( $zip_path, $restore_settings = true ) {
        global $wpdb;
        $report = array( 'courses' => 0, 'courses_missing' => array(), 'h5p_imported' => 0, 'h5p_reused' => 0, 'h5p_failed' => 0,
            'shortcodes' => 0, 'questions' => 0, 'video_links' => 0, 'settings' => 0, 'students' => 0, 'student_rows' => 0,
            'users_missing' => 0, 'attempts_missing' => 0, 'students_skipped' => false );
        $zip = new ZipArchive();
        if ( true !== $zip->open( $zip_path ) ) {
            throw new Exception( 'This is not a backup zip.' );
        }
        $manifest = json_decode( (string) $zip->getFromName( 'manifest.json' ), true );
        if ( ! is_array( $manifest ) || (int) ( $manifest['format'] ?? 0 ) !== self::FORMAT || empty( $manifest['backup_id'] ) ) {
            $zip->close();
            throw new Exception( 'This is not an EduQode App backup.' );
        }
        $bid = preg_replace( '/[^a-f0-9-]/', '', (string) $manifest['backup_id'] );

        // 1. Course ids on this site.
        $posts = array();
        $questions = array();
        foreach ( $manifest['courses'] as $outline ) {
            $map = self::map_ids( $outline );
            if ( ! $map ) {
                $report['courses_missing'][] = $outline['title'];
                continue;
            }
            $report['courses']++;
            $posts     += $map['posts'];
            $questions += $map['questions'];
        }

        // 2. H5P exercises (each one only once per backup).
        $h5p_map  = array();
        $imported = get_option( self::H5P_MAP_OPT, array() );
        foreach ( (array) $manifest['h5p'] as $h ) {
            $old = (int) $h['id'];
            $key = $bid . ':' . $old;
            if ( ! empty( $imported[ $key ] ) && $wpdb->get_var( $wpdb->prepare( "SELECT id FROM {$wpdb->prefix}h5p_contents WHERE id = %d", $imported[ $key ] ) ) ) {
                $h5p_map[ $old ] = (int) $imported[ $key ];
                $report['h5p_reused']++;
                continue;
            }
            $tmp = wp_tempnam( 'eduqode-h5p' );
            $ok  = EduQode_App_H5P::available() && false !== file_put_contents( $tmp, $zip->getFromName( 'h5p/' . $old . '.h5p' ) );
            $new = $ok ? self::import_h5p_file( $tmp, (string) $h['title'] ) : 0;
            @unlink( $tmp );
            if ( $new ) {
                $h5p_map[ $old ]  = $new;
                $imported[ $key ] = $new;
                $report['h5p_imported']++;
            } else {
                $report['h5p_failed']++;
            }
        }
        update_option( self::H5P_MAP_OPT, $imported, false );

        // 3. Lessons and H5P questions point to the new exercises.
        foreach ( $manifest['courses'] as $outline ) {
            foreach ( $outline['topics'] as $t ) {
                foreach ( $t['items'] as $i ) {
                    $new_id = $posts[ $i['id'] ] ?? 0;
                    if ( ! $new_id ) {
                        continue;
                    }
                    if ( 'tutor_quiz' !== $i['type'] ) {
                        $content = (string) get_post_field( 'post_content', $new_id );
                        $updated = self::remap_h5p_text( $content, $h5p_map );
                        if ( $updated !== $content ) {
                            $wpdb->update( $wpdb->posts, array( 'post_content' => $updated ), array( 'ID' => $new_id ) );
                            clean_post_cache( $new_id );
                            $report['shortcodes']++;
                        }
                    }
                    foreach ( (array) ( $i['questions'] ?? array() ) as $q ) {
                        $nq = $questions[ $q['id'] ] ?? 0;
                        if ( 'h5p' !== $q['type'] || ! $nq ) {
                            continue;
                        }
                        $desc = (string) $wpdb->get_var( $wpdb->prepare( "SELECT question_description FROM {$wpdb->prefix}tutor_quiz_questions WHERE question_id = %d", $nq ) );
                        $old  = EduQode_App_H5P::question_content_id( (object) array( 'question_description' => $desc ) );
                        if ( $old && isset( $h5p_map[ $old ] ) && trim( $desc ) === (string) $old ) {
                            $wpdb->update( $wpdb->prefix . 'tutor_quiz_questions', array( 'question_description' => (string) $h5p_map[ $old ] ), array( 'question_id' => $nq ) );
                            $report['questions']++;
                        }
                    }
                }
            }
        }

        // 4. Offline video links and app settings.
        foreach ( (array) $manifest['video_links'] as $old => $url ) {
            if ( isset( $posts[ (int) $old ] ) && EduQode_App_Courses::valid_url( $url ) ) {
                update_post_meta( $posts[ (int) $old ], EduQode_App_Courses::VIDEO_META, esc_url_raw( $url ) );
                $report['video_links']++;
            }
        }
        if ( $restore_settings ) {
            foreach ( (array) $manifest['settings'] as $opt => $value ) {
                if ( in_array( $opt, self::SETTINGS, true ) ) {
                    update_option( $opt, $value );
                    $report['settings']++;
                }
            }
        }

        // 5. Students' results (once per backup).
        if ( ! empty( $manifest['students'] ) ) {
            $done = get_option( self::IMPORTED_OPT, array() );
            if ( isset( $done[ $bid ] ) ) {
                $report['students_skipped'] = true;
            } else {
                self::import_students( $manifest['students'], $posts, $questions, $h5p_map, $report );
                $done[ $bid ] = time();
                update_option( self::IMPORTED_OPT, $done, false );
            }
        }
        $zip->close();
        return $report;
    }

    private static function import_students( $s, $posts, $questions, $h5p_map, &$report ) {
        global $wpdb;
        $p     = $wpdb->prefix;
        $users = array();
        $uid   = function ( $email ) use ( &$users ) {
            if ( ! isset( $users[ $email ] ) ) {
                $u = $email ? get_user_by( 'email', $email ) : false;
                $users[ $email ] = $u ? (int) $u->ID : 0;
            }
            return $users[ $email ];
        };
        $seen  = array();
        $count = function ( $email ) use ( &$seen, &$report ) {
            $seen[ $email ] = true;
            $report['student_rows']++;
        };
        $pid = function ( $old ) use ( $posts ) {
            return (int) ( $posts[ (int) $old ] ?? 0 );
        };

        foreach ( (array) $s['meta'] as $m ) {
            $u = $uid( $m['email'] );
            if ( ! $u ) {
                $report['users_missing']++;
                continue;
            }
            if ( 'h5p_finished' === $m['type'] ) {
                if ( $pid( $m['lesson'] ) ) {
                    update_user_meta( $u, '_tutor_h5p_lesson_completed_' . $pid( $m['lesson'] ), true );
                    $count( $m['email'] );
                }
                continue;
            }
            if ( in_array( $m['type'], array( EduQode_App_Sync::POSITIONS_META, EduQode_App_Sync::LAST_META ), true ) ) {
                $current = get_user_meta( $u, $m['type'], true );
                $current = is_array( $current ) ? $current : array();
                foreach ( (array) $m['value'] as $old => $v ) {
                    $new = $pid( $old );
                    if ( ! $new ) {
                        continue;
                    }
                    if ( EduQode_App_Sync::LAST_META === $m['type'] && isset( $v['l'] ) ) {
                        $v['l'] = $pid( $v['l'] );
                    }
                    if ( ! isset( $current[ $new ] ) || (int) $current[ $new ]['t'] < (int) ( $v['t'] ?? 0 ) ) {
                        $current[ $new ] = $v;
                    }
                }
                update_user_meta( $u, $m['type'], $current );
                $count( $m['email'] );
            }
        }

        if ( self::table_exists( 'h5p_results' ) ) {
            foreach ( (array) $s['h5p_results'] as $r ) {
                $u   = $uid( $r['email'] );
                $cid = $h5p_map[ (int) $r['content_id'] ] ?? 0;
                if ( ! $u || ! $cid ) {
                    $report['users_missing'] += $u ? 0 : 1;
                    continue;
                }
                $wpdb->insert( $p . 'h5p_results', array( 'content_id' => $cid, 'user_id' => $u, 'score' => $r['score'], 'max_score' => $r['max_score'],
                    'opened' => $r['opened'], 'finished' => $r['finished'], 'time' => $r['time'] ) );
                $count( $r['email'] );
            }
        }

        if ( ! self::table_exists( 'tutor_h5p_statement' ) ) {
            $report['students'] = count( $seen );
            return;
        }
        $base = function ( $r ) use ( $uid, $h5p_map ) {
            $row = $r;
            unset( $row['email'], $row['instructor_email'], $row['attempt_started_at'] );
            $row['user_id']       = $uid( $r['email'] );
            $row['content_id']    = (int) ( $h5p_map[ (int) $r['content_id'] ] ?? 0 );
            if ( array_key_exists( 'instructor_email', $r ) ) {
                $row['instructor_id'] = $uid( (string) $r['instructor_email'] );
            }
            return $row;
        };
        foreach ( (array) $s['statements'] as $r ) {
            $row = $base( $r );
            if ( $row['user_id'] && $row['content_id'] ) {
                $wpdb->insert( $p . 'tutor_h5p_statement', $row );
                $count( $r['email'] );
            }
        }
        foreach ( (array) $s['lesson_statements'] as $r ) {
            $row = $base( $r );
            $row['course_id'] = $pid( $r['course_id'] );
            $row['topic_id']  = $pid( $r['topic_id'] );
            $row['lesson_id'] = $pid( $r['lesson_id'] );
            if ( $row['user_id'] && $row['content_id'] && $row['lesson_id'] ) {
                $wpdb->insert( $p . 'tutor_h5p_lesson_statement', $row );
                $count( $r['email'] );
            }
        }
        // Quiz results belong to an attempt: find the same attempt (user, quiz, start time) imported by Tutor LMS Pro.
        $result_ids = array();
        foreach ( (array) $s['quiz_results'] as $r ) {
            $old_result = (int) $r['result_id'];
            $row        = $base( $r );
            unset( $row['result_id'] );
            $row['quiz_id']     = $pid( $r['quiz_id'] );
            $row['question_id'] = (int) ( $questions[ (int) $r['question_id'] ] ?? 0 );
            if ( ! $row['user_id'] || ! $row['quiz_id'] || ! $row['question_id'] || ! $row['content_id'] ) {
                continue;
            }
            $attempt = (int) $wpdb->get_var( $wpdb->prepare(
                "SELECT attempt_id FROM {$p}tutor_quiz_attempts WHERE user_id = %d AND quiz_id = %d AND attempt_started_at = %s ORDER BY attempt_id DESC LIMIT 1",
                $row['user_id'], $row['quiz_id'], (string) $r['attempt_started_at']
            ) );
            if ( ! $attempt ) {
                $report['attempts_missing']++;
                continue;
            }
            $row['attempt_id'] = $attempt;
            $wpdb->insert( $p . 'tutor_h5p_quiz_result', $row );
            $result_ids[ $old_result ] = (int) $wpdb->insert_id;
            $count( $r['email'] );
        }
        foreach ( (array) $s['quiz_statements'] as $r ) {
            $row                = $base( $r );
            $row['course_id']   = $pid( $r['course_id'] );
            $row['topic_id']    = $pid( $r['topic_id'] );
            $row['quiz_id']     = $pid( $r['quiz_id'] );
            $row['question_id'] = (int) ( $questions[ (int) $r['question_id'] ] ?? 0 );
            $row['quiz_result_id'] = isset( $r['quiz_result_id'] ) ? ( $result_ids[ (int) $r['quiz_result_id'] ] ?? null ) : null;
            if ( $row['user_id'] && $row['content_id'] && $row['quiz_id'] ) {
                $wpdb->insert( $p . 'tutor_h5p_quiz_statement', $row );
                $count( $r['email'] );
            }
        }
        $report['students'] = count( $seen );
    }
}
