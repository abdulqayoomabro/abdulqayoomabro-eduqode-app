<?php
// Site A: what Tutor LMS Pro's export carries for one course (posts, quiz questions/answers,
// students' enrollments and quiz attempts), so site B can imitate Tutor LMS Pro's import.
global $wpdb;
$course = (int) $args[0];
$posts = []; $queue = [$course];
while ($queue) {
    $id = array_shift($queue);
    $p = get_post($id);
    $posts[] = ['id' => $p->ID, 'type' => $p->post_type, 'title' => $p->post_title, 'slug' => $p->post_name, 'content' => $p->post_content,
        'parent' => $p->post_parent, 'order' => $p->menu_order, 'status' => $p->post_status];
    foreach ($wpdb->get_col($wpdb->prepare("SELECT ID FROM {$wpdb->posts} WHERE post_parent=%d AND post_type NOT IN ('revision','attachment') ORDER BY menu_order, ID", $id)) as $c) $queue[] = (int) $c;
}
$ids = implode(',', array_map(fn($p) => (int) $p['id'], $posts));
$questions = $wpdb->get_results("SELECT * FROM {$wpdb->prefix}tutor_quiz_questions WHERE quiz_id IN ($ids)", ARRAY_A);
$attempts = [];
foreach ($wpdb->get_results("SELECT * FROM {$wpdb->prefix}tutor_quiz_attempts WHERE quiz_id IN ($ids)", ARRAY_A) as $a) {
    $a['email'] = get_userdata($a['user_id'])->user_email; $attempts[] = $a;
}
$enrolled = [];
foreach ($wpdb->get_col($wpdb->prepare("SELECT post_author FROM {$wpdb->posts} WHERE post_type='tutor_enrolled' AND post_parent=%d", $course)) as $u) $enrolled[] = get_userdata($u)->user_email;
echo json_encode(compact('posts', 'questions', 'attempts', 'enrolled')), "\n";
