<?php
/**
 * TEST ONLY. Stand-in for Tutor LMS Pro 4.0.1's H5P addon (Tutor Pro is paid, so it is not in
 * this public test setup). Same table names/columns and the same filters, with the same logic as
 * TutorPro\H5P\Quiz::filter_total_marks / filter_total_quiz_marks / filter_quiz_answer_data and
 * TutorPro\H5P\Lesson::lesson_completion_restriction. The real addon was checked by hand.
 */
add_action('init', function () {
    global $wpdb;
    if ($wpdb->get_var("SHOW TABLES LIKE '{$wpdb->prefix}tutor_h5p_quiz_result'")) return;
    $stmt = "statement_id BIGINT(20) NOT NULL AUTO_INCREMENT, instructor_id BIGINT(20), content_id BIGINT(20), user_id BIGINT(20),
        verb VARCHAR(20), verb_id TEXT, activity_name TEXT, activity_description TEXT, activity_choices TEXT, activity_target TEXT,
        activity_interaction_type TEXT, activity_correct_response_pattern TEXT, result_response TEXT, result_max_score INT,
        result_raw_score INT, result_scaled_score INT, result_min_score INT, result_completion BOOLEAN, result_success BOOLEAN,
        result_duration TEXT, created_at DATETIME";
    $wpdb->query("CREATE TABLE {$wpdb->prefix}tutor_h5p_statement ($stmt, PRIMARY KEY (statement_id))");
    $wpdb->query("CREATE TABLE {$wpdb->prefix}tutor_h5p_lesson_statement ($stmt, course_id BIGINT(20), topic_id BIGINT(20), lesson_id BIGINT(20), PRIMARY KEY (statement_id))");
    $wpdb->query("CREATE TABLE {$wpdb->prefix}tutor_h5p_quiz_statement ($stmt, course_id BIGINT(20), topic_id BIGINT(20), quiz_id BIGINT(20), question_id BIGINT(20), quiz_result_id BIGINT(20), PRIMARY KEY (statement_id))");
    $wpdb->query("CREATE TABLE {$wpdb->prefix}tutor_h5p_quiz_result (result_id BIGINT(20) NOT NULL AUTO_INCREMENT, quiz_id BIGINT(20), attempt_id BIGINT(20),
        question_id BIGINT(20), user_id BIGINT(20), content_id BIGINT(20), response TEXT, max_score INT, raw_score INT, scaled_score INT, min_score INT,
        completion BOOLEAN, success BOOLEAN, opened INT(10), finished INT(10), duration BIGINT(20), PRIMARY KEY (result_id))");
});
function eduqode_standin_h5p_result($qid, $uid, $aid) {
    global $wpdb;
    return $wpdb->get_row($wpdb->prepare("SELECT * FROM {$wpdb->prefix}tutor_h5p_quiz_result WHERE question_id=%d AND user_id=%d AND attempt_id=%d", $qid, $uid, $aid));
}
add_filter('tutor_filter_update_before_question_mark', function ($total, $question_ids, $uid, $aid) {
    $answered = 0; $has = false;
    foreach ($question_ids as $qid) {
        $q = \Tutor\Models\QuizModel::get_quiz_question_by_id($qid);
        if ('h5p' === $q->question_type) {
            $has = true;
            if ($r = eduqode_standin_h5p_result($qid, $uid, $aid)) { $total += $r->max_score; $answered++; }
        }
    }
    return ($has && $answered < count($question_ids)) ? 0 : $total;
}, 10, 4);
add_filter('tutor_filter_quiz_total_marks', function ($total, $qid, $type, $uid, $aid) {
    if ('h5p' === $type && ($r = eduqode_standin_h5p_result($qid, $uid, $aid))) $total += $r->raw_score;
    return $total;
}, 10, 5);
add_filter('tutor_filter_quiz_answer_data', function ($data, $qid, $type, $uid, $aid) {
    if ('h5p' === $type && ($r = eduqode_standin_h5p_result($qid, $uid, $aid))) {
        $data['question_mark'] = $r->max_score; $data['achieved_mark'] = $r->raw_score; $data['is_correct'] = $r->max_score === $r->raw_score;
    }
    return $data;
}, 10, 5);
add_filter('tutor_validate_lesson_complete', function ($ok, $uid, $lesson_id) {
    if (!tutor_utils()->get_option('disable_complete_lesson_button')) return $ok;
    if (preg_match('/\[h5p id="\d+"\]/', (string) get_post_field('post_content', $lesson_id))
        && !get_user_meta($uid, '_tutor_h5p_lesson_completed_' . $lesson_id, true)) return false;
    return $ok;
}, 10, 3);
