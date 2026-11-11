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

$student = wp_insert_user(['user_login'=>'student','user_pass'=>'student','user_email'=>'s@b.co','role'=>'subscriber','display_name'=>'Ali Student']);
tutor_utils()->do_enroll($course, 0, $student);
update_option('eduqode_app_test_video_url', 'https://test.example.com/test.mp4');
update_option('eduqode_app_test_lesson_ids', (string) $l1);
echo json_encode(compact('course','t1','t2','l1','l2','l3','q','other','ol','student','pdf','mp4')), "\n";
