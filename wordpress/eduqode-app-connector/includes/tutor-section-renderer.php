<?php
/** Uses Tutor LMS's own (protected) renderers for the instructor home sections. */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

if ( ! class_exists( 'EduQode_Tutor_Sections' ) ) {
    class EduQode_Tutor_Sections extends \TUTOR\DashboardSectionManager {
        public static function stats( $cards, array $params ) {
            return parent::render_current_stats( $cards, $params );
        }

        public static function top( $courses, array $params ) {
            return parent::render_top_performing_courses( $courses, $params );
        }
    }
}
