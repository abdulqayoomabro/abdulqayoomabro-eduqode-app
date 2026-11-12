<?php
/**
 * Offline H5P (the free "H5P" plugin + Tutor LMS Pro's H5P addon).
 *
 * Package: for every H5P content used by a lesson ([h5p id="X"]) or by an H5P quiz question,
 * the app gets h5p.json + content.json and the list of library and content files, which it
 * downloads and plays with the open source h5p-standalone player.
 *
 * Sync: xAPI statements the app recorded offline are stored the same way Tutor LMS Pro stores
 * them on the website (tutor_h5p_statement, tutor_h5p_lesson_statement, tutor_h5p_quiz_statement,
 * tutor_h5p_quiz_result), the H5P plugin's own result table (h5p_results) is updated, and
 * "H5P lesson finished" sets the same user meta Tutor Pro uses to allow lesson completion.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_H5P {

    /** True when the H5P plugin is active. */
    public static function available() {
        return class_exists( 'H5P_Plugin' );
    }

    private static function table_exists( $table ) {
        global $wpdb;
        $name = $wpdb->prefix . $table;
        return $name === $wpdb->get_var( $wpdb->prepare( 'SHOW TABLES LIKE %s', $name ) );
    }

    /** Tutor LMS Pro's H5P addon is active (its tables exist). */
    public static function tutor_addon_active() {
        return self::table_exists( 'tutor_h5p_quiz_result' ) && self::table_exists( 'tutor_h5p_statement' );
    }

    /** Content ids used in lesson text, same pattern as Tutor Pro (plus single quotes / no quotes). */
    public static function ids_in_text( $text ) {
        $ids = array();
        if ( preg_match_all( '/\[h5p\s+id=["\']?(\d+)["\']?\s*\]/i', (string) $text, $m ) ) {
            foreach ( $m[1] as $id ) {
                $ids[] = (int) $id;
            }
        }
        return array_values( array_unique( $ids ) );
    }

    /** Lesson text without H5P shortcodes (the app shows the exercises below the text). */
    public static function strip_shortcodes( $text ) {
        return preg_replace( '/\[h5p\s+id=["\']?\d+["\']?\s*\]/i', '', (string) $text );
    }

    private static function core() {
        return H5P_Plugin::get_instance()->get_h5p_instance( 'core' );
    }

    private static function upload_base() {
        $up = wp_upload_dir();
        return array( trailingslashit( $up['basedir'] ) . 'h5p', trailingslashit( $up['baseurl'] ) . 'h5p' );
    }

    /** All files below a folder as [relative path => absolute path]. */
    private static function files_below( $dir ) {
        $out = array();
        if ( ! is_dir( $dir ) ) {
            return $out;
        }
        $it = new RecursiveIteratorIterator( new RecursiveDirectoryIterator( $dir, FilesystemIterator::SKIP_DOTS ) );
        foreach ( $it as $file ) {
            if ( ! $file->isFile() ) {
                continue;
            }
            $rel = ltrim( str_replace( '\\', '/', substr( $file->getPathname(), strlen( $dir ) ) ), '/' );
            // Only what the player needs (no editor files, translations or server scripts).
            if ( preg_match( '/\.(php|phtml|phar|sh)$/i', $rel ) || 0 === strpos( basename( $rel ), '.' )
                || preg_match( '#^(language/|semantics\.json$|upgrades\.js$|presave\.js$)#', $rel ) ) {
                continue;
            }
            $out[ $rel ] = $file->getPathname();
        }
        ksort( $out );
        return $out;
    }

    private static function url_for( $base_url, $rel ) {
        return $base_url . implode( '/', array_map( 'rawurlencode', explode( '/', $rel ) ) );
    }

    /**
     * Everything the app needs for one H5P content, or null if it cannot be played offline.
     * Library files are returned separately in $libraries (shared by all contents of a course).
     */
    public static function content( $content_id, &$libraries ) {
        global $wpdb;
        if ( ! self::available() ) {
            return null;
        }
        $core    = self::core();
        $content = $core->loadContent( (int) $content_id );
        if ( ! is_array( $content ) || empty( $content['library'] ) ) {
            return null;
        }
        // Builds "filtered" parameters and the dependency list, exactly like showing it on the website.
        $filtered = $core->filterParameters( $content );
        if ( ! is_string( $filtered ) || '' === $filtered ) {
            $filtered = (string) $content['params'];
        }

        $deps = $wpdb->get_results(
            $wpdb->prepare(
                "SELECT l.name, l.major_version, l.minor_version, cl.dependency_type, cl.weight
                 FROM {$wpdb->prefix}h5p_contents_libraries cl
                 JOIN {$wpdb->prefix}h5p_libraries l ON l.id = cl.library_id
                 WHERE cl.content_id = %d AND cl.dependency_type <> 'editor'
                 ORDER BY cl.weight",
                (int) $content_id
            )
        );
        list( $base_dir, $base_url ) = self::upload_base();

        $preloaded = array();
        $folders   = array();
        foreach ( $deps as $d ) {
            $folder = $d->name . '-' . $d->major_version . '.' . $d->minor_version;
            $preloaded[ $folder ] = array(
                'machineName'  => $d->name,
                'majorVersion' => (string) $d->major_version,
                'minorVersion' => (string) $d->minor_version,
            );
            $folders[ $folder ] = true;
        }
        $main   = $content['library'];
        $mfold  = $main['name'] . '-' . $main['majorVersion'] . '.' . $main['minorVersion'];
        if ( ! isset( $preloaded[ $mfold ] ) ) {
            $preloaded = array( $mfold => array(
                'machineName'  => $main['name'],
                'majorVersion' => (string) $main['majorVersion'],
                'minorVersion' => (string) $main['minorVersion'],
            ) ) + $preloaded;
            $folders[ $mfold ] = true;
        }

        foreach ( array_keys( $folders ) as $folder ) {
            if ( isset( $libraries[ $folder ] ) ) {
                continue;
            }
            $list = array();
            foreach ( self::files_below( $base_dir . '/libraries/' . $folder ) as $rel => $abs ) {
                $list[] = array(
                    'path' => $folder . '/' . $rel,
                    'url'  => self::url_for( $base_url . '/libraries/', $folder . '/' . $rel ),
                    'size' => (int) filesize( $abs ),
                );
            }
            if ( ! $list ) {
                return null; // library missing on the server
            }
            $libraries[ $folder ] = $list;
        }

        $files = array();
        foreach ( self::files_below( $base_dir . '/content/' . (int) $content_id ) as $rel => $abs ) {
            if ( 'content.json' === $rel ) {
                continue; // the app writes content.json from the filtered parameters
            }
            $files[] = array(
                'path' => $rel,
                'url'  => self::url_for( $base_url . '/content/' . (int) $content_id . '/', $rel ),
                'size' => (int) filesize( $abs ),
            );
        }

        $embed = (string) ( $content['embedType'] ?? 'div' );
        return array(
            'id'        => (int) $content_id,
            'title'     => (string) ( $content['title'] ?? '' ),
            'library'   => $main['name'],
            'libraries' => array_keys( $folders ),
            'h5p_json'  => array(
                'title'                 => (string) ( $content['title'] ?? '' ),
                'language'              => (string) ( $content['language'] ?? 'en' ),
                'mainLibrary'           => $main['name'],
                'embedTypes'            => array_values( array_filter( array_map( 'trim', explode( ',', $embed ) ) ) ) ?: array( 'div' ),
                'license'               => 'U',
                'preloadedDependencies' => array_values( $preloaded ),
            ),
            'content_json' => $filtered,
            'files'        => $files,
        );
    }

    // ---------------- sync ----------------

    /** Same mapping as Tutor LMS Pro's save_h5p_*_xAPI_statement(). */
    private static function statement_row( $statement, $content_id, $user_id, $course_id, $created ) {
        $instructors = $course_id ? tutor_utils()->get_instructors_by_course( $course_id ) : array();
        $row         = array(
            'content_id'    => (int) $content_id,
            'user_id'       => (int) $user_id,
            'created_at'    => $created,
            'instructor_id' => isset( $instructors[0] ) ? (int) $instructors[0]->ID : 0,
        );
        $loc = function ( $v ) {
            if ( is_object( $v ) ) {
                $v = (array) $v;
            }
            if ( is_array( $v ) ) {
                return (string) ( $v['en-US'] ?? reset( $v ) );
            }
            return (string) $v;
        };
        if ( isset( $statement->verb ) ) {
            if ( isset( $statement->verb->id ) ) {
                $row['verb_id'] = (string) $statement->verb->id;
            }
            if ( isset( $statement->verb->display ) ) {
                $row['verb'] = substr( $loc( $statement->verb->display ), 0, 20 );
            }
        }
        if ( isset( $statement->object->definition ) ) {
            $def = $statement->object->definition;
            if ( isset( $def->name ) ) {
                $row['activity_name'] = $loc( $def->name );
            } elseif ( isset( $def->description ) ) {
                $row['activity_name'] = $loc( $def->description );
            } else {
                $cat = $statement->context->contextActivities->category ?? null;
                $row['activity_name'] = is_array( $cat ) && $cat ? str_replace( 'http://h5p.org/libraries/', '', (string) $cat[0]->id ) : '';
            }
            $row['activity_description'] = isset( $def->description ) ? $loc( $def->description ) : '';
            if ( ! empty( $def->correctResponsesPattern ) && is_array( $def->correctResponsesPattern ) ) {
                $row['activity_correct_response_pattern'] = maybe_serialize( $def->correctResponsesPattern );
            }
            $choices = $def->choices ?? ( $def->source ?? null );
            if ( is_array( $choices ) && $choices ) {
                $row['activity_choices'] = maybe_serialize( $choices );
            }
            if ( ! empty( $def->target ) && is_array( $def->target ) ) {
                $row['activity_target'] = maybe_serialize( $def->target );
            }
            $row['activity_interaction_type'] = (string) ( $def->interactionType ?? '' );
        }
        if ( isset( $statement->result ) ) {
            $r = $statement->result;
            if ( isset( $r->score ) ) {
                $row['result_max_score']    = (int) ( $r->score->max ?? 0 );
                $row['result_min_score']    = (int) ( $r->score->min ?? 0 );
                $row['result_raw_score']    = (int) ( $r->score->raw ?? 0 );
                $row['result_scaled_score'] = (int) ( $r->score->scaled ?? 0 );
            }
            $row['result_completion'] = ! empty( $r->completion ) ? 1 : 0;
            $row['result_success']    = ! empty( $r->success ) ? 1 : 0;
            $row['result_response']   = isset( $r->response ) ? (string) $r->response : '';
            $row['result_duration']   = isset( $r->duration ) ? (string) $r->duration : '';
        }
        return $row;
    }

    private static function decode( $statement ) {
        if ( is_string( $statement ) ) {
            $statement = json_decode( $statement );
        } elseif ( is_array( $statement ) ) {
            $statement = json_decode( wp_json_encode( $statement ) );
        }
        return is_object( $statement ) ? $statement : null;
    }

    /** A top-level "answered"/"completed" statement with a score (what the H5P plugin counts as finished). */
    private static function is_final( $s ) {
        $verb   = strtolower( (string) ( $s->verb->id ?? '' ) );
        $parent = $s->context->contextActivities->parent ?? null;
        return ( false !== strpos( $verb, '/completed' ) || false !== strpos( $verb, '/answered' ) )
            && empty( $parent ) && isset( $s->result->score );
    }

    /** H5P plugin result table: one row per user and content, like its own "setFinished". */
    private static function save_h5p_result( $content_id, $user_id, $s, $opened, $finished ) {
        global $wpdb;
        if ( ! self::table_exists( 'h5p_results' ) ) {
            return;
        }
        $data = array(
            'score'     => (int) round( (float) ( $s->result->score->raw ?? 0 ) ),
            'max_score' => (int) round( (float) ( $s->result->score->max ?? 0 ) ),
            'opened'    => (int) $opened,
            'finished'  => (int) $finished,
            'time'      => max( 0, (int) $finished - (int) $opened ),
        );
        $id = $wpdb->get_var( $wpdb->prepare( "SELECT id FROM {$wpdb->prefix}h5p_results WHERE content_id = %d AND user_id = %d", $content_id, $user_id ) );
        if ( $id ) {
            $wpdb->update( $wpdb->prefix . 'h5p_results', $data, array( 'id' => (int) $id ) );
        } else {
            $wpdb->insert( $wpdb->prefix . 'h5p_results', $data + array( 'content_id' => (int) $content_id, 'user_id' => (int) $user_id ) );
        }
    }

    private static function created_at( $event ) {
        $t = (int) floor( (int) ( $event['client_time'] ?? 0 ) / 1000 );
        $t = ( $t > 0 && $t <= time() ) ? $t : time();
        return gmdate( 'Y-m-d H:i:s', $t );
    }

    /** Lesson content ids that a lesson really contains. */
    private static function lesson_ids( $lesson_id ) {
        return self::ids_in_text( (string) get_post_field( 'post_content', $lesson_id ) );
    }

    /**
     * Event "h5p_statement": {course_id, lesson_id, content_id, statement, opened_at}.
     * @return array [status, reason]
     */
    public static function record_lesson_statement( $event, $user_id ) {
        global $wpdb;
        $lesson_id  = (int) ( $event['lesson_id'] ?? 0 );
        $course_id  = (int) ( $event['course_id'] ?? 0 );
        $content_id = (int) ( $event['content_id'] ?? 0 );
        if ( ! in_array( $content_id, self::lesson_ids( $lesson_id ), true ) ) {
            return array( 'rejected', 'not_in_lesson' );
        }
        $s = self::decode( $event['statement'] ?? null );
        if ( ! $s ) {
            return array( 'rejected', 'bad_statement' );
        }
        $created = self::created_at( $event );
        if ( self::is_final( $s ) ) {
            $finished = strtotime( $created . ' UTC' );
            $opened   = (int) floor( (int) ( $event['opened_at'] ?? 0 ) / 1000 ) ?: $finished;
            self::save_h5p_result( $content_id, $user_id, $s, min( $opened, $finished ), $finished );
        }
        if ( ! self::tutor_addon_active() ) {
            return array( 'applied', '' );
        }
        $row = self::statement_row( $s, $content_id, $user_id, $course_id, $created );
        $wpdb->insert( $wpdb->prefix . 'tutor_h5p_statement', $row );
        $wpdb->insert( $wpdb->prefix . 'tutor_h5p_lesson_statement', $row + array(
            'course_id' => $course_id,
            'lesson_id' => $lesson_id,
            'topic_id'  => (int) wp_get_post_parent_id( $lesson_id ),
        ) );
        return array( 'applied', '' );
    }

    /** Event "h5p_lesson_finished": all H5P of the lesson done (Tutor Pro then allows completion). */
    public static function record_lesson_finished( $event, $user_id ) {
        $lesson_id = (int) ( $event['lesson_id'] ?? 0 );
        if ( ! self::lesson_ids( $lesson_id ) ) {
            return array( 'rejected', 'not_h5p_lesson' );
        }
        update_user_meta( $user_id, '_tutor_h5p_lesson_completed_' . $lesson_id, true );
        return array( 'applied', '' );
    }

    /** Content id of a Tutor Pro H5P quiz question (the description holds the id). */
    public static function question_content_id( $question ) {
        $desc = trim( wp_strip_all_tags( (string) $question->question_description ) );
        if ( ctype_digit( $desc ) ) {
            return (int) $desc;
        }
        $ids = self::ids_in_text( $desc );
        return $ids ? $ids[0] : 0;
    }

    /**
     * Stores the H5P answers of an offline quiz attempt before Tutor grades it, the way
     * Tutor Pro's save_h5p_question_xAPI_statement() does on the website.
     * $h5p = [question_id => ['content_id'=>..,'opened_at'=>..,'statements'=>[...]]]
     */
    public static function record_quiz_answers( $h5p, $quiz_id, $attempt_id, $course_id, $user_id, $event ) {
        global $wpdb;
        if ( ! self::tutor_addon_active() ) {
            return;
        }
        $topic_id = (int) wp_get_post_parent_id( $quiz_id );
        $created  = self::created_at( $event );
        foreach ( $h5p as $question_id => $answer ) {
            $content_id = (int) ( $answer['content_id'] ?? 0 );
            $result_id  = 0;
            foreach ( (array) ( $answer['statements'] ?? array() ) as $raw ) {
                $s = self::decode( $raw );
                if ( ! $s ) {
                    continue;
                }
                if ( isset( $s->result ) && ! $result_id ) {
                    $finished = strtotime( $created . ' UTC' );
                    $opened   = (int) floor( (int) ( $answer['opened_at'] ?? 0 ) / 1000 ) ?: $finished;
                    $opened   = min( $opened, $finished );
                    $wpdb->insert( $wpdb->prefix . 'tutor_h5p_quiz_result', array(
                        'quiz_id'      => (int) $quiz_id,
                        'question_id'  => (int) $question_id,
                        'content_id'   => $content_id,
                        'user_id'      => (int) $user_id,
                        'attempt_id'   => (int) $attempt_id,
                        'max_score'    => (int) ( $s->result->score->max ?? 0 ),
                        'min_score'    => (int) ( $s->result->score->min ?? 0 ),
                        'raw_score'    => (int) ( $s->result->score->raw ?? 0 ),
                        'scaled_score' => (int) ( $s->result->score->scaled ?? 0 ),
                        'completion'   => ! empty( $s->result->completion ) ? 1 : 0,
                        'success'      => ! empty( $s->result->success ) ? 1 : 0,
                        'response'     => isset( $s->result->response ) ? (string) $s->result->response : '',
                        'opened'       => $opened,
                        'finished'     => $finished,
                        'duration'     => $finished - $opened,
                    ) );
                    $result_id = (int) $wpdb->insert_id;
                    if ( self::is_final( $s ) ) {
                        self::save_h5p_result( $content_id, $user_id, $s, $opened, $finished );
                    }
                }
                $row = self::statement_row( $s, $content_id, $user_id, $course_id, $created );
                $wpdb->insert( $wpdb->prefix . 'tutor_h5p_statement', $row );
                $wpdb->insert( $wpdb->prefix . 'tutor_h5p_quiz_statement', $row + array(
                    'quiz_id'        => (int) $quiz_id,
                    'question_id'    => (int) $question_id,
                    'course_id'      => (int) $course_id,
                    'topic_id'       => $topic_id,
                    'quiz_result_id' => $result_id ?: null,
                ) );
            }
        }
    }
}
