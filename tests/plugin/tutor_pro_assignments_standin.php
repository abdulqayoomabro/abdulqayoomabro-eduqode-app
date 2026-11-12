<?php
/**
 * TEST ONLY. Stand-in for Tutor LMS Pro 4.0.1's Assignments addon submit (Tutor Pro is paid, so it
 * is not in this public test setup): same class, method names and stored data as
 * TUTOR_ASSIGNMENTS\Assignments::update_assignment_submit() / handle_assignment_attachment_uploads().
 * The real addon was checked by hand.
 */
namespace TUTOR_ASSIGNMENTS;

if (class_exists(__NAMESPACE__ . '\Assignments')) return;

class Assignments {
    public static function is_assignment_expired(int $assignment_id, int $user_id = 0, int $course_id = 0): bool { return false; }

    public function handle_assignment_attachment_uploads($assignment_id = 0) {
        require_once ABSPATH . 'wp-admin/includes/file.php';
        $out = [];
        $files = $_FILES['attached_assignment_files'] ?? null;
        if (!$files) return $out;
        foreach ($files['name'] as $k => $name) {
            $file = ['name' => $name, 'type' => $files['type'][$k], 'tmp_name' => $files['tmp_name'][$k],
                'error' => $files['error'][$k], 'size' => $files['size'][$k]];
            $m = wp_handle_upload($file, ['test_form' => false]);
            if ($m && !isset($m['error'])) {
                $up = wp_get_upload_dir();
                $m['uploaded_path'] = str_replace(trailingslashit($up['basedir']), '', $m['file']);
                $m['name'] = basename($m['file']); unset($m['file']);
                $out[] = $m;
            }
        }
        return $out;
    }

    public function update_assignment_submit($d) {
        global $wpdb;
        do_action('tutor_assignment/before/submit', $d->assignment_submit_id);
        $files = $this->handle_assignment_attachment_uploads($d->assignment_id);
        if ($d->allowed_upload_files && $files) update_comment_meta($d->assignment_submit_id, 'uploaded_attachments', json_encode($files, JSON_UNESCAPED_UNICODE));
        $wpdb->update($wpdb->comments, ['comment_content' => $d->assignment_answer, 'comment_date' => wp_date('Y-m-d H:i:s'), 'comment_approved' => 'submitted'],
            ['comment_ID' => $d->assignment_submit_id]);
        do_action('tutor_assignment/after/submitted', $d->assignment_submit_id);
        return $d->assignment_id;
    }
}
