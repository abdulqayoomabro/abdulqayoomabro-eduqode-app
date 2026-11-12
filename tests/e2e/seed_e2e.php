<?php
// Test course for the emulator end-to-end test. Files are real: a playable MP4 (served slowly with
// HTTP Range support by slow.php so pause/resume can be tested), a valid PDF and a PNG image.
$base = home_url();                       // http://10.0.2.2:8099 (the emulator's view of this computer)
$dir  = ABSPATH . 'wp-content/uploads/e2e';
wp_mkdir_p($dir);

// Valid one-page PDF built by hand (correct xref offsets so Android's PdfRenderer accepts it).
$objs = [
  "<< /Type /Catalog /Pages 2 0 R >>",
  "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
  "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
  null,
  "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
];
$stream = "BT /F1 18 Tf 40 100 Td (EduQode offline PDF) Tj ET";
$objs[3] = "<< /Length " . strlen($stream) . " >>\nstream\n$stream\nendstream";
$pdf = "%PDF-1.4\n"; $offsets = [];
foreach ($objs as $i => $o) { $offsets[] = strlen($pdf); $pdf .= ($i + 1) . " 0 obj\n$o\nendobj\n"; }
$xref = strlen($pdf);
$pdf .= "xref\n0 " . (count($objs) + 1) . "\n0000000000 65535 f \n";
foreach ($offsets as $off) $pdf .= sprintf("%010d 00000 n \n", $off);
$pdf .= "trailer\n<< /Size " . (count($objs) + 1) . " /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n";
file_put_contents("$dir/notes.pdf", $pdf);
file_put_contents("$dir/pic.png", base64_decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='));

$pdf_id = wp_insert_attachment(['post_title' => 'Lesson notes', 'post_mime_type' => 'application/pdf'], "$dir/notes.pdf");
$img = "$base/wp-content/uploads/e2e/pic.png";
$video = "$base/slow.php?f=big.mp4";

$course = wp_insert_post(['post_type' => 'courses', 'post_status' => 'publish', 'post_title' => 'E2E Offline Course', 'post_content' => '<p>Course for offline testing.</p>', 'post_author' => 1]);
update_post_meta($course, '_tutor_course_price_type', 'free');
$t1 = wp_insert_post(['post_type' => 'topics', 'post_status' => 'publish', 'post_title' => 'Part 1', 'post_parent' => $course, 'menu_order' => 0]);
$l1 = wp_insert_post(['post_type' => 'lesson', 'post_status' => 'publish', 'post_title' => 'YouTube lesson', 'post_parent' => $t1, 'menu_order' => 0,
    'post_content' => '<p>OFFLINE-TEXT-MARKER</p><img src="' . $img . '">']);
update_post_meta($l1, '_video', ['source' => 'youtube', 'source_youtube' => 'https://www.youtube.com/watch?v=abc123']);
update_post_meta($l1, '_tutor_attachments', [$pdf_id]);
$l2 = wp_insert_post(['post_type' => 'lesson', 'post_status' => 'publish', 'post_title' => 'Own video lesson', 'post_parent' => $t1, 'menu_order' => 1, 'post_content' => '<p>Video lesson text</p>']);
update_post_meta($l2, '_video', ['source' => 'youtube', 'source_youtube' => 'https://youtu.be/xyz']);
update_post_meta($l2, '_eduqode_offline_video_url', $video);
$q = wp_insert_post(['post_type' => 'tutor_quiz', 'post_status' => 'publish', 'post_title' => 'Quiz', 'post_parent' => $t1, 'menu_order' => 2]);
$l3 = wp_insert_post(['post_type' => 'lesson', 'post_status' => 'publish', 'post_title' => 'Text lesson', 'post_parent' => $t1, 'menu_order' => 3, 'post_content' => '<p>Read me offline</p>']);

// Offline quiz (auto-graded types) to compare the phone's result with Tutor's.
global $wpdb;
function e2e_question($quiz, $type, $title, $mark, $order, $answers) {
    global $wpdb;
    $wpdb->insert($wpdb->prefix . 'tutor_quiz_questions', ['quiz_id' => $quiz, 'question_title' => $title, 'question_description' => '',
        'question_type' => $type, 'question_mark' => $mark, 'question_settings' => maybe_serialize([]), 'question_order' => $order]);
    $qid = (int) $wpdb->insert_id;
    foreach ($answers as $i => $a) {
        $wpdb->insert($wpdb->prefix . 'tutor_quiz_question_answers', ['belongs_question_id' => $qid, 'belongs_question_type' => $type,
            'answer_title' => $a[0], 'is_correct' => $a[1], 'answer_two_gap_match' => $a[2] ?? '', 'answer_order' => $i + 1]);
    }
}
$quiz = wp_insert_post(['post_type' => 'tutor_quiz', 'post_status' => 'publish', 'post_title' => 'Offline Quiz', 'post_parent' => $t1, 'menu_order' => 4]);
update_post_meta($quiz, 'tutor_quiz_option', ['time_limit' => ['time_type' => 'minutes', 'time_value' => 5], 'limit_attempts_allowed' => '1',
    'attempts_allowed' => 2, 'passing_grade' => 60, 'questions_order' => 'sorting']);
e2e_question($quiz, 'true_false', 'Water is wet', 1, 1, [['True', 1], ['False', 0]]);
e2e_question($quiz, 'single_choice', '2 + 2', 1, 2, [['3', 0], ['4', 1]]);
e2e_question($quiz, 'multiple_choice', 'Even numbers', 2, 3, [['2', 1], ['3', 0], ['4', 1]]);
e2e_question($quiz, 'fill_in_the_blank', 'Fill', 1, 4, [['The sky is {dash}', 0, 'blue']]);
e2e_question($quiz, 'ordering', 'Count up', 1, 5, [['1', 0], ['2', 0], ['3', 0]]);

// Assignment with a dataset (Tutor LMS Pro style).
file_put_contents("$dir/data.csv", "x,y\n1,2\n3,4\n");
$csv_id = wp_insert_attachment(['post_title' => 'Dataset', 'post_mime_type' => 'text/csv'], "$dir/data.csv");
$asg = wp_insert_post(['post_type' => 'tutor_assignments', 'post_status' => 'publish', 'post_title' => 'Dataset exercise', 'post_parent' => $t1, 'menu_order' => 5,
    'post_content' => '<p>ASSIGNMENT-MARKER Analyse the dataset.</p>']);
update_post_meta($asg, '_tutor_assignment_attachments', [$csv_id]);
update_post_meta($asg, 'assignment_option', ['total_mark' => 10, 'pass_mark' => 5, 'upload_files_limit' => 2, 'upload_file_size_limit' => 5]);

// Published during the test, to check that downloaded courses pick up new content.
$l4 = wp_insert_post(['post_type' => 'lesson', 'post_status' => 'draft', 'post_title' => 'Lesson added later', 'post_parent' => $t1, 'menu_order' => 6,
    'post_content' => '<p>NEW-LESSON-MARKER</p>']);

$student = wp_insert_user(['user_login' => 'e2estudent', 'user_pass' => 'e2estudent', 'user_email' => 'e2e@b.co', 'role' => 'subscriber', 'display_name' => 'E2E Student']);
tutor_utils()->do_enroll($course, 0, $student);
// H5P course: a lesson with a real True/False exercise and a Tutor LMS Pro style H5P quiz.
// Lessons with H5P can be completed only after their exercises (Tutor setting).
$h5p_id = (int) $wpdb->get_var("SELECT id FROM {$wpdb->prefix}h5p_contents ORDER BY id LIMIT 1");
$hcourse = wp_insert_post(['post_type' => 'courses', 'post_status' => 'publish', 'post_title' => 'E2E H5P Course', 'post_author' => 1]);
update_post_meta($hcourse, '_tutor_course_price_type', 'free');
$ht = wp_insert_post(['post_type' => 'topics', 'post_status' => 'publish', 'post_title' => 'Practice', 'post_parent' => $hcourse]);
$hl = wp_insert_post(['post_type' => 'lesson', 'post_status' => 'publish', 'post_title' => 'Interactive lesson', 'post_parent' => $ht, 'menu_order' => 0,
    'post_content' => '<p>H5P-LESSON-MARKER</p>[h5p id="' . $h5p_id . '"]']);
$hq = wp_insert_post(['post_type' => 'tutor_quiz', 'post_status' => 'publish', 'post_title' => 'H5P quiz', 'post_parent' => $ht, 'menu_order' => 1]);
update_post_meta($hq, 'tutor_quiz_option', ['quiz_type' => 'tutor_h5p_quiz', 'limit_attempts_allowed' => '1', 'attempts_allowed' => 0, 'passing_grade' => 50, 'questions_order' => 'sorting']);
$wpdb->insert($wpdb->prefix . 'tutor_quiz_questions', ['quiz_id' => $hq, 'question_title' => 'Exercise question', 'question_description' => (string) $h5p_id,
    'question_type' => 'h5p', 'question_mark' => 1, 'question_settings' => maybe_serialize(['answer_required' => 1]), 'question_order' => 1]);
$hqq = (int) $wpdb->insert_id;
tutor_utils()->do_enroll($hcourse, 0, $student);
$opt = get_option('tutor_option', []); $opt['disable_complete_lesson_button'] = 1; update_option('tutor_option', $opt);

$ids = compact('course', 'l1', 'l2', 'l3', 'l4', 'q', 'quiz', 'asg', 'student', 'hcourse', 'hl', 'hq', 'hqq', 'h5p_id');
file_put_contents(ABSPATH . 'e2e-ids.json', json_encode($ids));
echo json_encode($ids), "\n";
