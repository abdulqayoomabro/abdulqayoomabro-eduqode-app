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

$student = wp_insert_user(['user_login' => 'e2estudent', 'user_pass' => 'e2estudent', 'user_email' => 'e2e@b.co', 'role' => 'subscriber', 'display_name' => 'E2E Student']);
tutor_utils()->do_enroll($course, 0, $student);
$ids = compact('course', 'l1', 'l2', 'l3', 'q', 'student');
file_put_contents(ABSPATH . 'e2e-ids.json', json_encode($ids));
echo json_encode($ids), "\n";
