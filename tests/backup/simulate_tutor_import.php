<?php
// Site B: does what Tutor LMS Pro's import does for the backup test: same course with NEW ids,
// lesson text unchanged (still [h5p id="<site A id>"]), questions unchanged, students'
// enrollments and quiz attempts (same start time) for users with the same email.
global $wpdb;
$d = json_decode(file_get_contents($args[0]), true);
$map = [];
foreach ($d['posts'] as $p) {
    $map[$p['id']] = wp_insert_post(['post_type' => $p['type'], 'post_title' => $p['title'], 'post_name' => $p['slug'], 'post_content' => $p['content'],
        'post_status' => $p['status'], 'menu_order' => $p['order'], 'post_parent' => $p['parent'] ? ($map[$p['parent']] ?? 0) : 0, 'post_author' => 1]);
}
$qmap = [];
// Only columns site B has (a newer Tutor LMS on site A can have more, e.g. content_id).
$wpdb->get_results("SELECT * FROM {$wpdb->prefix}tutor_quiz_questions LIMIT 0");
$cols = (array) $wpdb->get_col_info('name');
foreach ($d['questions'] as $q) {
    if ($cols) { $q = array_intersect_key($q, array_flip($cols)); }
    $old = $q['question_id']; unset($q['question_id']); $q['quiz_id'] = $map[$q['quiz_id']];
    $wpdb->insert($wpdb->prefix . 'tutor_quiz_questions', $q); $qmap[$old] = (int) $wpdb->insert_id;
}
$course = $map[$d['posts'][0]['id']];
foreach ($d['enrolled'] as $email) { $u = get_user_by('email', $email); if ($u) tutor_utils()->do_enroll($course, 0, $u->ID); }
foreach ($d['attempts'] as $a) {
    $u = get_user_by('email', $a['email']); if (!$u) continue;
    unset($a['attempt_id'], $a['email']); $a['user_id'] = $u->ID; $a['quiz_id'] = $map[$a['quiz_id']]; $a['course_id'] = $course;
    $wpdb->insert($wpdb->prefix . 'tutor_quiz_attempts', $a);
}
echo json_encode(['posts' => $map, 'questions' => $qmap]), "\n";
