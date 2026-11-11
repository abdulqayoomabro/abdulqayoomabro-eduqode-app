<?php
/**
 * Course data for the app, built only from Tutor LMS helper functions that exist in
 * Tutor LMS (checked against Tutor LMS 4.2 source): get_enrolled_courses_ids_by_user,
 * is_enrolled, get_topics, get_course_contents_by_topic, get_video, get_attachments,
 * is_completed_lesson, get_course_completed_percent, get_course_id_by.
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

class EduQode_App_Courses {

    const VIDEO_META = '_eduqode_offline_video_url';

    /** Whether offline downloads are switched on in Settings > EduQode App. */
    public static function offline_enabled() {
        return '0' !== get_option( 'eduqode_app_offline_enabled', '1' );
    }

    /**
     * Tutor LMS Pro "Content Drip": a lesson or quiz that is not released yet for this student
     * (by date, or X days after enrolling) is left out of the offline pack, the same as on the website.
     * Uses Tutor Pro's own ContentDrip::is_lock_lesson() (Tutor LMS Pro 4.0.1).
     */
    public static function is_drip_locked( $content_id ) {
        static $drip = false;
        if ( false === $drip ) {
            $drip = null;
            global $wp_filter;
            if ( ! empty( $wp_filter['tutor_lesson_show_mark_as_complete'] ) ) {
                foreach ( $wp_filter['tutor_lesson_show_mark_as_complete']->callbacks as $callbacks ) {
                    foreach ( $callbacks as $cb ) {
                        $fn = $cb['function'];
                        if ( is_array( $fn ) && is_object( $fn[0] ) && method_exists( $fn[0], 'is_lock_lesson' ) ) {
                            $drip = $fn[0];
                        }
                    }
                }
            }
        }
        return $drip ? (bool) $drip->is_lock_lesson( $content_id ) : false;
    }

    /** Enrolled students, plus admins and the course's own instructors (for testing). */
    public static function can_access( $course_id, $user_id ) {
        $course_id = (int) $course_id;
        if ( ! $course_id || get_post_type( $course_id ) !== tutor()->course_post_type ) {
            return false;
        }
        if ( user_can( $user_id, 'manage_options' ) || user_can( $user_id, 'edit_post', $course_id ) ) {
            return true;
        }
        return (bool) tutor_utils()->is_enrolled( $course_id, $user_id );
    }

    public static function enrolled_course_ids( $user_id ) {
        $ids = tutor_utils()->get_enrolled_courses_ids_by_user( $user_id );
        $ids = is_array( $ids ) ? array_map( 'intval', $ids ) : array();
        return array_values( array_unique( array_filter( $ids ) ) );
    }

    public static function course_summary( $course_id, $user_id ) {
        $post = get_post( $course_id );
        return array(
            'id'            => (int) $course_id,
            'title'         => html_entity_decode( get_the_title( $course_id ), ENT_QUOTES, 'UTF-8' ),
            'excerpt'       => wp_strip_all_tags( get_the_excerpt( $course_id ) ),
            'thumbnail'     => (string) get_the_post_thumbnail_url( $course_id, 'medium_large' ),
            'permalink'     => get_permalink( $course_id ),
            'categories'    => self::categories( $course_id ),
            'modified'      => $post ? mysql2date( 'U', $post->post_modified_gmt ) * 1000 : 0,
            'progress'      => (int) tutor_utils()->get_course_completed_percent( $course_id, $user_id ),
            'lesson_count'  => self::count_lessons( $course_id ),
            'offline'       => self::offline_enabled(),
        );
    }

    private static function categories( $course_id ) {
        $terms = get_the_terms( $course_id, 'course-category' );
        if ( ! is_array( $terms ) ) {
            return array();
        }
        return array_values( wp_list_pluck( $terms, 'name' ) );
    }

    /** Topics (in order) with their lessons and quizzes (in order). */
    public static function structure( $course_id ) {
        $topics = array();
        $query  = tutor_utils()->get_topics( $course_id );
        foreach ( $query->posts as $t_index => $topic ) {
            $items    = array();
            $contents = tutor_utils()->get_course_contents_by_topic( $topic->ID, -1 );
            foreach ( $contents->posts as $i_index => $item ) {
                $items[] = $item;
            }
            $topics[] = array(
                'post'  => $topic,
                'order' => $t_index,
                'items' => $items,
            );
        }
        return $topics;
    }

    private static function count_lessons( $course_id ) {
        $count = 0;
        foreach ( self::structure( $course_id ) as $topic ) {
            foreach ( $topic['items'] as $item ) {
                if ( $item->post_type === tutor()->lesson_post_type ) {
                    $count++;
                }
            }
        }
        return $count;
    }

    /** True if $lesson_id is a lesson or quiz inside $course_id. */
    public static function lesson_in_course( $lesson_id, $course_id ) {
        $type = get_post_type( $lesson_id );
        if ( $type === tutor()->lesson_post_type ) {
            return (int) tutor_utils()->get_course_id_by( 'lesson', $lesson_id ) === (int) $course_id;
        }
        if ( 'tutor_quiz' === $type ) {
            return (int) tutor_utils()->get_course_id_by( 'quiz', $lesson_id ) === (int) $course_id;
        }
        return false;
    }

    /**
     * Video for a lesson.
     *  online:  how the lesson video plays on the website (YouTube, Vimeo, ...)
     *  offline: a file the app may download, or null ("Online only").
     *
     * Offline sources, in order:
     *  1. URL entered in Settings > EduQode App > Offline videos (_eduqode_offline_video_url)
     *  2. Test video (Settings > EduQode App > Test mode) for the listed lesson IDs
     *  3. Tutor "HTML5 (mp4)" video uploaded to this WordPress site
     *  4. Tutor "External URL" video that points directly to an .mp4/.webm file
     * YouTube and Vimeo videos are never downloaded.
     *
     * Other providers (Bunny Stream, S3, ...) can be added with the
     * 'eduqode_app_offline_video' filter, which receives ($offline, $lesson_id, $user_id).
     */
    public static function video( $lesson_id, $user_id ) {
        $video  = tutor_utils()->get_video( $lesson_id );
        $video  = is_array( $video ) ? $video : array();
        $source = isset( $video['source'] ) ? (string) $video['source'] : '';
        if ( '-1' === $source ) {
            $source = '';
        }

        $online = null;
        if ( 'youtube' === $source && ! empty( $video['source_youtube'] ) ) {
            $online = array( 'provider' => 'youtube', 'url' => esc_url_raw( $video['source_youtube'] ) );
        } elseif ( 'vimeo' === $source && ! empty( $video['source_vimeo'] ) ) {
            $online = array( 'provider' => 'vimeo', 'url' => esc_url_raw( $video['source_vimeo'] ) );
        } elseif ( $source ) {
            $online = array( 'provider' => $source, 'url' => '' );
        }

        $offline = null;
        $custom  = trim( (string) get_post_meta( $lesson_id, self::VIDEO_META, true ) );
        if ( $custom && self::valid_url( $custom ) ) {
            $offline = self::direct( $custom, 'lesson_setting' );
        }

        if ( ! $offline ) {
            $test_url = trim( (string) get_option( 'eduqode_app_test_video_url', '' ) );
            $test_ids = array_map( 'intval', preg_split( '/[\s,]+/', (string) get_option( 'eduqode_app_test_lesson_ids', '' ), -1, PREG_SPLIT_NO_EMPTY ) );
            if ( $test_url && self::valid_url( $test_url ) && in_array( (int) $lesson_id, $test_ids, true ) ) {
                $offline = self::direct( $test_url, 'test_mode' );
            }
        }

        if ( ! $offline && 'html5' === $source && ! empty( $video['source_video_id'] ) ) {
            $att_id = (int) $video['source_video_id'];
            $url    = wp_get_attachment_url( $att_id );
            if ( $url ) {
                $offline = self::direct( $url, 'tutor_html5' );
                $file    = get_attached_file( $att_id );
                if ( $file && file_exists( $file ) ) {
                    $offline['size_bytes'] = (int) filesize( $file );
                }
                if ( ! $online ) {
                    $online = array( 'provider' => 'html5', 'url' => $url );
                }
            }
        }

        if ( ! $offline && 'external_url' === $source && ! empty( $video['source_external_url'] ) ) {
            $url  = esc_url_raw( $video['source_external_url'] );
            $path = strtolower( (string) wp_parse_url( $url, PHP_URL_PATH ) );
            if ( preg_match( '/\.(mp4|webm|m4v)$/', $path ) ) {
                $offline = self::direct( $url, 'tutor_external' );
            }
        }

        $offline = apply_filters( 'eduqode_app_offline_video', $offline, $lesson_id, $user_id );

        return array(
            'online'  => $online,
            'offline' => $offline,
        );
    }

    /**
     * http(s) URL check without a DNS lookup (wp_http_validate_url resolves the host,
     * which is slow and rejects some valid CDN and own-server addresses).
     */
    public static function valid_url( $url ) {
        if ( ! is_string( $url ) || ! filter_var( $url, FILTER_VALIDATE_URL ) ) {
            return false;
        }
        $scheme = strtolower( (string) wp_parse_url( $url, PHP_URL_SCHEME ) );
        return in_array( $scheme, array( 'http', 'https' ), true );
    }

    private static function direct( $url, $origin ) {
        return array(
            'provider'   => 'direct',
            'url'        => esc_url_raw( $url ),
            'origin'     => $origin,
            'size_bytes' => 0,
            'mime'       => 'video/mp4',
        );
    }

    /** Lesson text as HTML, with images listed so the app can store them. */
    private static function lesson_html( $post ) {
        $html = (string) $post->post_content;
        if ( function_exists( 'do_blocks' ) ) {
            $html = do_blocks( $html );
        }
        $html = wpautop( do_shortcode( $html ) );
        // srcset/sizes point to many image sizes; the app stores only the main src.
        $html = preg_replace( '/\s(srcset|sizes)=("[^"]*"|\'[^\']*\')/i', '', $html );

        $images = array();
        if ( preg_match_all( '/<img[^>]+src=["\']([^"\']+)["\']/i', $html, $m ) ) {
            foreach ( $m[1] as $src ) {
                $src = html_entity_decode( $src, ENT_QUOTES, 'UTF-8' );
                if ( 0 === strpos( $src, '//' ) ) {
                    $src = 'https:' . $src;
                } elseif ( 0 === strpos( $src, '/' ) ) {
                    $src = home_url( $src );
                }
                if ( self::valid_url( $src ) ) {
                    $images[] = $src;
                }
            }
        }
        return array( $html, array_values( array_unique( $images ) ) );
    }

    private static function attachments( $lesson_id ) {
        $list = array();
        foreach ( (array) tutor_utils()->get_attachments( $lesson_id ) as $att ) {
            $att = (array) $att;
            if ( empty( $att['url'] ) ) {
                continue;
            }
            $id     = (int) ( isset( $att['id'] ) ? $att['id'] : 0 );
            $list[] = array(
                'id'         => $id,
                'url'        => esc_url_raw( $att['url'] ),
                'name'       => isset( $att['name'] ) ? (string) $att['name'] : basename( (string) $att['url'] ),
                'size_bytes' => isset( $att['size_bytes'] ) ? (int) $att['size_bytes'] : 0,
                'mime'       => $id ? (string) get_post_mime_type( $id ) : '',
            );
        }
        return $list;
    }

    /** Everything the app needs to show the course with no internet. */
    public static function package( $course_id, $user_id ) {
        $course = get_post( $course_id );
        $sync   = new EduQode_App_Sync( $user_id );

        $description = (string) $course->post_content;
        if ( function_exists( 'do_blocks' ) ) {
            $description = do_blocks( $description );
        }
        $description = wpautop( do_shortcode( $description ) );

        $topics = array();
        foreach ( self::structure( $course_id ) as $topic ) {
            $items = array();
            foreach ( $topic['items'] as $order => $item ) {
                /**
                 * Leave a lesson out of offline packs (for example content that is not released yet).
                 * Filter: eduqode_app_include_lesson( true, $lesson_id, $user_id, $course_id ).
                 */
                $include = ! self::is_drip_locked( $item->ID );
                if ( ! apply_filters( 'eduqode_app_include_lesson', $include, $item->ID, $user_id, $course_id ) ) {
                    continue;
                }
                $is_lesson = $item->post_type === tutor()->lesson_post_type;
                $entry     = array(
                    'id'           => (int) $item->ID,
                    'type'         => $is_lesson ? 'lesson' : ( 'tutor_quiz' === $item->post_type ? 'quiz' : $item->post_type ),
                    'title'        => html_entity_decode( get_the_title( $item ), ENT_QUOTES, 'UTF-8' ),
                    'order'        => $order,
                    'permalink'    => get_permalink( $item ),
                    'modified'     => mysql2date( 'U', $item->post_modified_gmt ) * 1000,
                    'online_only'  => ! $is_lesson,
                    'completed_at' => 0,
                );
                if ( $is_lesson ) {
                    $done                  = tutor_utils()->is_completed_lesson( $item->ID, $user_id );
                    $entry['completed_at'] = $done ? ( is_numeric( $done ) ? (int) $done * 1000 : 1 ) : 0;
                    list( $html, $images ) = self::lesson_html( $item );
                    $entry['content_html'] = $html;
                    $entry['images']       = $images;
                    $entry['attachments']  = self::attachments( $item->ID );
                    $entry['video']        = self::video( $item->ID, $user_id );
                    $entry['position']     = $sync->position( $item->ID );
                } elseif ( 'tutor_quiz' === $item->post_type ) {
                    $reason = '';
                    $images = array();
                    $quiz   = EduQode_App_Quiz::package( $item->ID, $user_id, $reason, $images );
                    if ( $quiz ) {
                        $entry['online_only'] = false;
                        $entry['quiz']        = $quiz;
                        $entry['images']      = array_values( array_unique( array_filter( $images, array( __CLASS__, 'valid_url' ) ) ) );
                    } else {
                        $entry['online_reason'] = $reason;
                    }
                }
                $items[] = $entry;
            }
            $topics[] = array(
                'id'      => (int) $topic['post']->ID,
                'title'   => html_entity_decode( get_the_title( $topic['post'] ), ENT_QUOTES, 'UTF-8' ),
                'summary' => wp_strip_all_tags( $topic['post']->post_content ),
                'order'   => $topic['order'],
                'items'   => $items,
            );
        }

        $summary                = self::course_summary( $course_id, $user_id );
        $summary['description'] = $description;

        return array(
            'package_version' => 1,
            'generated_at'    => time() * 1000,
            'course'          => $summary,
            'topics'          => $topics,
            'last_lesson'     => $sync->last_lesson( $course_id ),
        );
    }
}
