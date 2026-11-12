<?php
/**
 * Wraps visible English text of a plugin file in a translation function (uses PHP's own tokenizer).
 *   php wrap.php <file.php> <function>     e.g. php wrap.php class-eqs-front.php eqs__
 * - text between HTML tags and placeholder="…" inside '…' strings
 * - plain sentences like 'Saved.' or 'Delete this term?' (not array keys, constants, SQL or option names)
 * Already wrapped text is left alone, so running it twice changes nothing.
 */
[$self, $file, $fn] = $argv;
$tokens = token_get_all( file_get_contents( $file ) );
$out = '';
$inConst = false;

function prev_sig( $tokens, $i ) {
    for ( $j = $i - 1; $j >= 0; $j-- ) {
        if ( is_array( $tokens[ $j ] ) && in_array( $tokens[ $j ][0], array( T_WHITESPACE, T_COMMENT, T_DOC_COMMENT ), true ) ) continue;
        return array( $j, $tokens[ $j ] );
    }
    return array( -1, null );
}
function next_sig( $tokens, $i ) {
    for ( $j = $i + 1; $j < count( $tokens ); $j++ ) {
        if ( is_array( $tokens[ $j ] ) && in_array( $tokens[ $j ][0], array( T_WHITESPACE, T_COMMENT, T_DOC_COMMENT ), true ) ) continue;
        return $tokens[ $j ];
    }
    return null;
}
function is_sentence( $s ) {
    if ( preg_match( '/\b(SELECT|FROM|WHERE|ORDER BY|INSERT|CREATE|JOIN|GROUP BY|LIMIT|UPDATE)\b/', $s ) ) return false;
    if ( preg_match( '/[_{}$=\\\\\/]/', str_replace( "\\'", '', $s ) ) ) return false;
    if ( ! preg_match( '/[a-z]{2,}/', $s ) ) return false;
    if ( preg_match( '/^[A-Za-z0-9-]+:?\s*$/', $s ) && false !== strpos( $s, '-' ) ) return false;   // header or date format
    return (bool) preg_match( '/^[\s←(]*[A-Z]/u', $s );
}
function wrap_text( $raw, $fn ) {
    // Part of a tag split over two strings ('<a href="' . $url . '">'): not text.
    if ( false !== strpbrk( $raw, '<>"=' ) ) return $raw;
    if ( ! preg_match( '/[A-Za-z]{2,}/', $raw ) || ! preg_match( '/^(\s*)(.*?)(\s*)$/s', $raw, $m ) ) return $raw;
    if ( '' === $m[2] || preg_match( '/^[\s·—–:()0-9%.,]+$/u', $m[2] ) ) return $raw;
    return $m[1] . "' . esc_html( $fn( '" . $m[2] . "' ) ) . '" . $m[3];
}

function wrap_inline_html( $html, $fn ) {
    $parts = preg_split( '/(<script.*?<\/script>|<style.*?<\/style>|<[^>]*>)/s', $html, -1, PREG_SPLIT_DELIM_CAPTURE );
    $out   = '';
    foreach ( $parts as $p ) {
        if ( '' !== $p && '<' === $p[0] ) {
            $out .= preg_replace_callback( '/placeholder="([^"]*[A-Za-z]{2,}[^"]*)"/', function ( $m ) use ( $fn ) {
                return 'placeholder="<?php echo esc_attr( ' . $fn . "( '" . str_replace( "'", "\\'", $m[1] ) . "' ) ); ?>\"";
            }, $p );
            continue;
        }
        if ( false !== strpbrk( $p, '<>"=' ) || ! preg_match( '/[A-Za-z]{2,}/', $p ) || ! preg_match( '/^(\s*)(.*?)(\s*)$/s', $p, $m ) || preg_match( '/^[\s·—–:()0-9%.,]+$/u', $m[2] ) ) {
            $out .= $p;
            continue;
        }
        $text = preg_replace( '/\s+/', ' ', $m[2] );
        $out .= $m[1] . '<?php echo esc_html( ' . $fn . "( '" . str_replace( "'", "\\'", $text ) . "' ) ); ?>" . $m[3];
    }
    return $out;
}

foreach ( $tokens as $i => $t ) {
    if ( is_array( $t ) && T_INLINE_HTML === $t[0] && false === strpos( $t[1], $fn . '(' ) ) {
        $out .= wrap_inline_html( $t[1], $fn );
        continue;
    }
    if ( is_array( $t ) && T_CONST === $t[0] ) $inConst = true;
    if ( ! is_array( $t ) && ';' === $t ) $inConst = false;
    if ( ! is_array( $t ) || T_CONSTANT_ENCAPSED_STRING !== $t[0] || "'" !== $t[1][0] || $inConst ) {
        $out .= is_array( $t ) ? $t[1] : $t;
        continue;
    }
    $inner = substr( $t[1], 1, -1 );
    [ $pj, $prev ] = prev_sig( $tokens, $i );
    $next = next_sig( $tokens, $i );
    $alreadyWrapped = '(' === $prev && ( [ , $pp ] = prev_sig( $tokens, $pj ) ) && is_array( $pp ) && in_array( $pp[1], array( $fn, '__', 'translate', 'esc_html__', '_e' ), true );
    if ( $alreadyWrapped || ( is_array( $next ) && T_DOUBLE_ARROW === $next[0] ) ) {
        $out .= $t[1];
        continue;
    }
    if ( false !== strpbrk( $inner, '<>' ) ) {
        $parts = preg_split( '/(<[^>]*>)/', $inner, -1, PREG_SPLIT_DELIM_CAPTURE );
        $new   = '';
        foreach ( $parts as $p ) {
            if ( '' !== $p && '<' === $p[0] && '>' === substr( $p, -1 ) ) {
                $new .= preg_replace_callback( '/placeholder="([^"\']*[A-Za-z]{2,}[^"\']*)"/', function ( $m ) use ( $fn ) {
                    return 'placeholder="\' . esc_attr( ' . $fn . "( '" . $m[1] . "' ) ) . '\"";
                }, $p );
            } elseif ( preg_match( '/(onclick|onsubmit|onchange)=/', $inner ) && preg_match( '/[(){};]/', $p ) ) {
                $new .= $p; // JavaScript, not text
            } else {
                $new .= wrap_text( $p, $fn );
            }
        }
        $out .= "'" . $new . "'";
        continue;
    }
    if ( is_sentence( $inner ) ) {
        $out .= "$fn( '" . $inner . "' )";
        continue;
    }
    $out .= $t[1];
}
file_put_contents( $file, $out );
