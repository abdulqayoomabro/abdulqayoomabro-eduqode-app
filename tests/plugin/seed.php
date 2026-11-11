<?php
// Creates a Tutor LMS course like the real site: YouTube lesson, HTML5 mp4 lesson,
// lesson with offline link, a quiz, a PDF attachment and an image in lesson text.
$up = wp_upload_dir();
file_put_contents($up['path'].'/notes.pdf', "%PDF-1.4\n%test\n");
file_put_contents($up['path'].'/clip.mp4', str_repeat('x', 2048));
file_put_contents($up['path'].'/pic.png', base64_decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='));
$pdf = wp_insert_attachment(['post_title'=>'Notes','post_mime_type'=>'application/pdf'], $up['path'].'/notes.pdf');
$mp4 = wp_insert_attachment(['post_title'=>'Clip','post_mime_type'=>'video/mp4'], $up['path'].'/clip.mp4');
$png = wp_insert_attachment(['post_title'=>'Pic','post_mime_type'=>'image/png'], $up['path'].'/pic.png');
$img = wp_get_attachment_url($png);

$course = wp_insert_post(['post_type'=>'courses','post_status'=>'publish','post_title'=>'Python Basics','post_content'=>'<p>Learn Python.</p>','post_author'=>1]);
update_post_meta($course, '_tutor_course_price_type', 'free');
$t1 = wp_insert_post(['post_type'=>'topics','post_status'=>'publish','post_title'=>'Getting started','post_parent'=>$course,'menu_order'=>0]);
$t2 = wp_insert_post(['post_type'=>'topics','post_status'=>'publish','post_title'=>'Next steps','post_parent'=>$course,'menu_order'=>1]);
$l1 = wp_insert_post(['post_type'=>'lesson','post_status'=>'publish','post_title'=>'Intro (YouTube)','post_parent'=>$t1,'menu_order'=>0,
    'post_content'=>'<p>Welcome</p><img src="'.$img.'" srcset="'.$img.' 2x" alt="x">']);
update_post_meta($l1, '_video', ['source'=>'youtube','source_youtube'=>'https://www.youtube.com/watch?v=abc123']);
update_post_meta($l1, '_tutor_attachments', [$pdf]);
$l2 = wp_insert_post(['post_type'=>'lesson','post_status'=>'publish','post_title'=>'Install (own mp4)','post_parent'=>$t1,'menu_order'=>1,'post_content'=>'<p>Install python</p>']);
update_post_meta($l2, '_video', ['source'=>'html5','source_video_id'=>$mp4]);
$q = wp_insert_post(['post_type'=>'tutor_quiz','post_status'=>'publish','post_title'=>'Quiz 1','post_parent'=>$t1,'menu_order'=>2]);
$l3 = wp_insert_post(['post_type'=>'lesson','post_status'=>'publish','post_title'=>'Variables (YouTube + offline link)','post_parent'=>$t2,'menu_order'=>0,'post_content'=>'<p>Vars</p>']);
update_post_meta($l3, '_video', ['source'=>'youtube','source_youtube'=>'https://youtu.be/xyz']);
update_post_meta($l3, '_eduqode_offline_video_url', 'https://cdn.example.com/vars.mp4');

$other = wp_insert_post(['post_type'=>'courses','post_status'=>'publish','post_title'=>'Not enrolled course','post_author'=>1]);
$ot = wp_insert_post(['post_type'=>'topics','post_status'=>'publish','post_title'=>'T','post_parent'=>$other]);
$ol = wp_insert_post(['post_type'=>'lesson','post_status'=>'publish','post_title'=>'Other lesson','post_parent'=>$ot]);

// ---- Quizzes with real Tutor LMS questions (all types the app supports offline) ----
global $wpdb;
function eq_question($quiz, $type, $title, $mark, $order, $answers) {
    global $wpdb;
    $wpdb->insert($wpdb->prefix . 'tutor_quiz_questions', ['quiz_id' => $quiz, 'question_title' => $title, 'question_description' => '',
        'question_type' => $type, 'question_mark' => $mark, 'question_settings' => maybe_serialize(['answer_required' => 1]), 'question_order' => $order]);
    $qid = (int) $wpdb->insert_id; $ids = [];
    foreach ($answers as $i => $a) {
        $wpdb->insert($wpdb->prefix . 'tutor_quiz_question_answers', ['belongs_question_id' => $qid, 'belongs_question_type' => $type,
            'answer_title' => $a[0], 'is_correct' => $a[1], 'answer_two_gap_match' => $a[2] ?? '', 'answer_order' => $i + 1, 'image_id' => $a[3] ?? 0]);
        $ids[] = (int) $wpdb->insert_id;
    }
    return [$qid, $ids];
}
// Quiz 2: unlimited attempts, includes a short answer (teacher review).
$quiz2 = wp_insert_post(['post_type' => 'tutor_quiz', 'post_status' => 'publish', 'post_title' => 'Offline Quiz', 'post_parent' => $t2, 'menu_order' => 1]);
update_post_meta($quiz2, 'tutor_quiz_option', ['time_limit' => ['time_type' => 'minutes', 'time_value' => 10], 'limit_attempts_allowed' => '1',
    'attempts_allowed' => 0, 'passing_grade' => 50, 'max_questions_for_answer' => 0, 'questions_order' => 'sorting']);
$Q = [];
$Q['tf']  = eq_question($quiz2, 'true_false', 'Python is a language', 1, 1, [['True', 1], ['False', 0]]);
$Q['sc']  = eq_question($quiz2, 'single_choice', 'Pick B', 1, 2, [['A', 0], ['B', 1], ['C', 0]]);
$Q['mc']  = eq_question($quiz2, 'multiple_choice', 'Pick A and C', 2, 3, [['A', 1], ['B', 0], ['C', 1]]);
$Q['fib'] = eq_question($quiz2, 'fill_in_the_blank', 'Fill', 1, 4, [['Python is {dash} and {dash}', 0, 'easy|fun']]);
$Q['ord'] = eq_question($quiz2, 'ordering', 'Order', 1, 5, [['One', 0], ['Two', 0], ['Three', 0]]);
$Q['mat'] = eq_question($quiz2, 'matching', 'Match', 1, 6, [['Cat', 0, 'Meow'], ['Dog', 0, 'Woof']]);
$Q['ia']  = eq_question($quiz2, 'image_answering', 'Name the city', 1, 7, [['Lahore', 0, '', $png]]);
$Q['sa']  = eq_question($quiz2, 'short_answer', 'Explain', 2, 8, []);
// Quiz 3: one attempt only, auto-graded.
$quiz3 = wp_insert_post(['post_type' => 'tutor_quiz', 'post_status' => 'publish', 'post_title' => 'One Try Quiz', 'post_parent' => $t2, 'menu_order' => 2]);
update_post_meta($quiz3, 'tutor_quiz_option', ['limit_attempts_allowed' => '1', 'attempts_allowed' => 1, 'passing_grade' => 50, 'questions_order' => 'sorting']);
$Q3 = eq_question($quiz3, 'true_false', 'Sky is blue', 1, 1, [['True', 1], ['False', 0]]);
// Quiz 4: a question type that needs the website (stays online only).
$quiz4 = wp_insert_post(['post_type' => 'tutor_quiz', 'post_status' => 'publish', 'post_title' => 'H5P Quiz', 'post_parent' => $t2, 'menu_order' => 3]);
eq_question($quiz4, 'h5p', 'H5P item', 1, 1, []);
file_put_contents(sys_get_temp_dir() . '/eduqode_quiz_ids.json', json_encode(['quiz2' => $quiz2, 'quiz3' => $quiz3, 'quiz4' => $quiz4, 'Q' => $Q, 'Q3' => $Q3]));

$student = wp_insert_user(['user_login'=>'student','user_pass'=>'student','user_email'=>'s@b.co','role'=>'subscriber','display_name'=>'Ali Student']);
tutor_utils()->do_enroll($course, 0, $student);
update_option('eduqode_app_test_video_url', 'https://test.example.com/test.mp4');
update_option('eduqode_app_test_lesson_ids', (string) $l1);
echo json_encode(array_merge(compact('course','t1','t2','l1','l2','l3','q','other','ol','student','pdf','mp4'), ['quizzes' => json_decode(file_get_contents(sys_get_temp_dir() . '/eduqode_quiz_ids.json'), true)])), "\n";
