<?php
// H5P test course: one lesson with an H5P exercise and one Tutor LMS Pro style H5P quiz.
// Usage: wp eval-file seed_h5p.php <h5p content id> <student id>
global $wpdb;
$cid     = (int) $args[0];
$student = (int) $args[1];
$course  = wp_insert_post(['post_type'=>'courses','post_status'=>'publish','post_title'=>'H5P Course','post_author'=>1]);
update_post_meta($course, '_tutor_course_price_type', 'free');
$topic   = wp_insert_post(['post_type'=>'topics','post_status'=>'publish','post_title'=>'Practice','post_parent'=>$course]);
$lesson  = wp_insert_post(['post_type'=>'lesson','post_status'=>'publish','post_title'=>'Interactive lesson','post_parent'=>$topic,'menu_order'=>0,
    'post_content'=>'<p>Read H5P-LESSON-TEXT then answer.</p>[h5p id="' . $cid . '"]']);
$quiz    = wp_insert_post(['post_type'=>'tutor_quiz','post_status'=>'publish','post_title'=>'H5P practice quiz','post_parent'=>$topic,'menu_order'=>1]);
update_post_meta($quiz, 'tutor_quiz_option', ['quiz_type'=>'tutor_h5p_quiz','limit_attempts_allowed'=>'1','attempts_allowed'=>0,'passing_grade'=>50,'questions_order'=>'sorting']);
$wpdb->insert($wpdb->prefix.'tutor_quiz_questions', ['quiz_id'=>$quiz,'question_title'=>'Sky question','question_description'=>(string) $cid,
    'question_type'=>'h5p','question_mark'=>1,'question_settings'=>maybe_serialize(['answer_required'=>1]),'question_order'=>1]);
$question = (int) $wpdb->insert_id;
tutor_utils()->do_enroll($course, 0, $student);
echo json_encode(['h5p_course'=>$course,'h5p_lesson'=>$lesson,'h5p_quiz'=>$quiz,'h5p_question'=>$question,'h5p_content'=>$cid]), "\n";
