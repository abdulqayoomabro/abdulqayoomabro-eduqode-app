<?php
// Imports an .h5p file into the H5P plugin the same way its admin upload does.
// Usage: wp eval-file h5p_import.php <file.h5p> <title>   → prints the new content id
wp_set_current_user(1);
$file   = $args[0];
$plugin = H5P_Plugin::get_instance();
$iface  = $plugin->get_h5p_instance('interface');
$valid  = $plugin->get_h5p_instance('validator');
$path   = $iface->getUploadedH5pPath();
@mkdir(dirname($path), 0777, true);
copy($file, $path);
if (!$valid->isValidPackage(false, false)) {
    fwrite(STDERR, "invalid package: " . print_r($iface->getMessages('error'), true));
    exit(1);
}
$content = ['metadata' => ['title' => $args[1] ?? 'H5P'], 'disable' => 0];
$storage = $plugin->get_h5p_instance('storage');
$storage->savePackage($content, null, false);
echo (int) $storage->contentId;
