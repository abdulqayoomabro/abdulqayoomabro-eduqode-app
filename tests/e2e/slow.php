<?php
// Serves a test file slowly (about 1.5 MB/s) with HTTP Range support, like a real CDN would,
// so the app's pause / resume / retry can be tested reliably.
$name = basename($_GET['f'] ?? '');
$file = __DIR__ . '/wp-content/uploads/e2e/' . $name;
if ($name === '' || !is_file($file)) { http_response_code(404); exit; }
$size = filesize($file);
$start = 0; $end = $size - 1; $code = 200;
if (isset($_SERVER['HTTP_RANGE']) && preg_match('/bytes=(\d+)-(\d*)/', $_SERVER['HTTP_RANGE'], $m)) {
    $start = (int) $m[1];
    if ($m[2] !== '') $end = min((int) $m[2], $size - 1);
    if ($start >= $size) { http_response_code(416); header("Content-Range: bytes */$size"); exit; }
    $code = 206;
}
http_response_code($code);
header('Content-Type: video/mp4');
header('Accept-Ranges: bytes');
header('Content-Length: ' . ($end - $start + 1));
if ($code === 206) header("Content-Range: bytes $start-$end/$size");
if ($_SERVER['REQUEST_METHOD'] === 'HEAD') exit;
while (ob_get_level()) ob_end_clean();
$fh = fopen($file, 'rb');
fseek($fh, $start);
$left = $end - $start + 1;
while ($left > 0 && !connection_aborted()) {
    $chunk = fread($fh, min(65536, $left));
    if ($chunk === false || $chunk === '') break;
    echo $chunk;
    flush();
    $left -= strlen($chunk);
    usleep(40000);
}
