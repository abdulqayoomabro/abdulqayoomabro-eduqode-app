<?php
global $wpdb;
$wpdb->query("DELETE FROM {$wpdb->usermeta} WHERE user_id=2 AND (meta_key LIKE '_tutor_completed_lesson_id_%' OR meta_key LIKE '_eduqode_app_%' OR meta_key='_lesson_reading_info')");
wp_cache_flush();
echo "reset\n";
