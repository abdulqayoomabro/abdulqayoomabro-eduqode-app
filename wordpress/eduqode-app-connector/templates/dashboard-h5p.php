<?php
/**
 * Tutor LMS dashboard → Interactive exercises (H5P): the instructor's H5P exercises and the
 * H5P editor (see EduQode_App_H5P_Studio).
 */

if ( ! defined( 'ABSPATH' ) ) {
    exit;
}

$eq_uid   = get_current_user_id();
$eq_edit  = isset( $_GET['edit'] ) ? absint( $_GET['edit'] ) : 0; // phpcs:ignore
$eq_new   = ! empty( $_GET['new'] ); // phpcs:ignore
$eq_saved = isset( $_GET['saved'] ) ? absint( $_GET['saved'] ) : 0; // phpcs:ignore
$eq_items = EduQode_App_H5P_Studio::contents( $eq_uid );
$eq_usage = EduQode_App_H5P_Studio::usage();
$eq_mine  = wp_list_pluck( $eq_items, 'id' );
$eq_can   = user_can( $eq_uid, 'edit_h5p_contents' );
?>
<div class="eduqode-h5p">
    <style>
        .eduqode-h5p{--eq-line:#e3e5e8;--eq-muted:#5b616b}
        .eduqode-h5p .eq-head{display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between;margin-bottom:16px}
        .eduqode-h5p h2{margin:0;font-size:22px}
        .eduqode-h5p .eq-btn{display:inline-block;padding:8px 16px;border-radius:8px;background:var(--tutor-color-primary,#3e64de);color:#fff!important;text-decoration:none;border:0;cursor:pointer;font-size:14px;line-height:1.4}
        .eduqode-h5p .eq-btn.eq-light{background:transparent;color:var(--tutor-color-primary,#3e64de)!important;border:1px solid currentColor}
        .eduqode-h5p .eq-btn.eq-danger{background:transparent;color:#b42318!important;border:1px solid #f3c4bf}
        .eduqode-h5p .eq-btn[disabled]{opacity:.45;cursor:not-allowed}
        .eduqode-h5p .eq-note{padding:12px 14px;border-radius:8px;margin:0 0 14px;background:#ecf6ef;border:1px solid #bfe3c8}
        .eduqode-h5p .eq-note.eq-err{background:#fdf1f0;border-color:#f3c4bf}
        .eduqode-h5p .eq-tip{color:var(--eq-muted);font-size:13px;margin:0 0 14px}
        .eduqode-h5p .eq-scroll{overflow-x:auto}
        .eduqode-h5p table{width:100%;border-collapse:collapse;font-size:14px}
        .eduqode-h5p th,.eduqode-h5p td{padding:10px 8px;border-bottom:1px solid var(--eq-line);text-align:start;vertical-align:middle}
        .eduqode-h5p th{font-weight:600;color:var(--eq-muted);font-size:12px;text-transform:uppercase;letter-spacing:.03em}
        .eduqode-h5p code{background:#f3f4f6;padding:2px 6px;border-radius:4px;user-select:all;white-space:nowrap}
        .eduqode-h5p .eq-actions{display:flex;gap:6px;flex-wrap:wrap}
        .eduqode-h5p .eq-actions form{margin:0}
        .eduqode-h5p iframe{width:100%;height:80vh;min-height:620px;border:1px solid var(--eq-line);border-radius:10px;background:#fff}
        .eduqode-h5p .eq-empty{padding:32px;text-align:center;border:1px dashed var(--eq-line);border-radius:10px;color:var(--eq-muted)}
    </style>

    <?php if ( ! $eq_can ) : ?>
        <div class="eq-note eq-err"><?php echo esc_html( eqa__( 'Only approved instructors can make interactive exercises.' ) ); ?></div>
    <?php elseif ( $eq_new || ( $eq_edit && in_array( (string) $eq_edit, array_map( 'strval', $eq_mine ), true ) ) ) : ?>
        <?php
        $eq_src = admin_url( 'admin.php?page=h5p_new&eduqode_frame=1' . ( $eq_edit ? '&id=' . $eq_edit : '' ) );
        ?>
        <div class="eq-head">
            <h2><?php echo esc_html( $eq_edit ? eqa__( 'Edit exercise' ) : eqa__( 'New exercise' ) ); ?></h2>
            <a class="eq-btn eq-light" href="<?php echo esc_url( EduQode_App_H5P_Studio::url() ); ?>"><?php echo esc_html( eqa__( 'Back to my exercises' ) ); ?></a>
        </div>
        <p class="eq-tip"><?php echo esc_html( eqa__( 'Choose a content type, fill it in, then press Create / Save at the right (on a phone: at the bottom).' ) ); ?></p>
        <iframe id="eq-h5p-editor" src="<?php echo esc_url( $eq_src ); ?>" title="<?php echo esc_attr( eqa__( 'H5P editor' ) ); ?>"></iframe>
        <script>
            window.addEventListener('message', function (e) {
                if (e.origin !== window.location.origin || !e.data || e.data.eduqodeH5P !== 'saved') { return; }
                var u = new URL(<?php echo wp_json_encode( EduQode_App_H5P_Studio::url() ); ?>);
                if (e.data.id) { u.searchParams.set('saved', e.data.id); }
                window.location.href = u.toString();
            });
        </script>
    <?php else : ?>
        <div class="eq-head">
            <h2><?php echo esc_html( eqa__( 'Interactive exercises (H5P)' ) ); ?></h2>
            <a class="eq-btn" href="<?php echo esc_url( EduQode_App_H5P_Studio::url( array( 'new' => 1 ) ) ); ?>">+ <?php echo esc_html( eqa__( 'New exercise' ) ); ?></a>
        </div>

        <?php if ( $eq_saved ) : ?>
            <div class="eq-note"><?php echo esc_html( eqa__( 'Saved. To use it in a lesson, paste this code into the lesson text:' ) ); ?> <code>[h5p id="<?php echo (int) $eq_saved; ?>"]</code> <?php echo esc_html( eqa__( 'For a quiz, add a question of type H5P in the course builder and choose it.' ) ); ?></div>
        <?php endif; ?>
        <?php if ( ! empty( $_GET['deleted'] ) ) : // phpcs:ignore ?>
            <div class="eq-note"><?php echo esc_html( eqa__( 'Exercise deleted.' ) ); ?></div>
        <?php endif; ?>
        <?php if ( 'in_use' === ( $_GET['error'] ?? '' ) ) : // phpcs:ignore ?>
            <div class="eq-note eq-err"><?php echo esc_html( eqa__( 'This exercise is used in a lesson or quiz. Remove it there first.' ) ); ?></div>
        <?php endif; ?>

        <p class="eq-tip"><?php echo esc_html( eqa__( 'Exercises work in the website and offline in the app. You can use the content types your admin installed.' ) ); ?></p>

        <?php if ( ! $eq_items ) : ?>
            <div class="eq-empty"><?php echo esc_html( eqa__( 'No exercises yet. Make your first one: quiz, drag and drop, fill in the blanks, image hotspots and more.' ) ); ?></div>
        <?php else : ?>
            <div class="eq-scroll"><table>
                <thead><tr>
                    <th><?php echo esc_html( eqa__( 'Title' ) ); ?></th>
                    <th><?php echo esc_html( eqa__( 'Type' ) ); ?></th>
                    <th><?php echo esc_html( eqa__( 'Code for lessons' ) ); ?></th>
                    <th><?php echo esc_html( eqa__( 'Used in' ) ); ?></th>
                    <th><?php echo esc_html( eqa__( 'Updated' ) ); ?></th>
                    <th></th>
                </tr></thead>
                <tbody>
                <?php foreach ( $eq_items as $eq_row ) : ?>
                    <?php $eq_used = (int) ( $eq_usage[ (int) $eq_row->id ] ?? 0 ); ?>
                    <tr>
                        <td><strong><?php echo esc_html( $eq_row->title ); ?></strong>
                            <?php if ( (int) $eq_row->user_id !== $eq_uid ) : ?><br><small><?php echo esc_html( (string) get_the_author_meta( 'display_name', (int) $eq_row->user_id ) ); ?></small><?php endif; ?></td>
                        <td><?php echo esc_html( (string) $eq_row->type ); ?></td>
                        <td><code>[h5p id="<?php echo (int) $eq_row->id; ?>"]</code></td>
                        <td><?php echo esc_html( sprintf( eqa__( '%d lesson(s) / question(s)' ), $eq_used ) ); ?></td>
                        <td><?php echo esc_html( mysql2date( get_option( 'date_format' ), (string) $eq_row->updated_at ) ); ?></td>
                        <td><div class="eq-actions">
                            <a class="eq-btn eq-light" href="<?php echo esc_url( EduQode_App_H5P_Studio::url( array( 'edit' => (int) $eq_row->id ) ) ); ?>"><?php echo esc_html( eqa__( 'Edit' ) ); ?></a>
                            <form method="post" action="<?php echo esc_url( EduQode_App_H5P_Studio::url() ); ?>" onsubmit="return confirm(<?php echo esc_attr( wp_json_encode( eqa__( 'Delete this exercise? Students\' results for it are kept.' ) ) ); ?>)">
                                <?php wp_nonce_field( 'eduqode_h5p_delete_' . (int) $eq_row->id ); ?>
                                <input type="hidden" name="eduqode_h5p_delete" value="<?php echo (int) $eq_row->id; ?>">
                                <button type="submit" class="eq-btn eq-danger" <?php disabled( $eq_used > 0 ); ?> title="<?php echo esc_attr( $eq_used ? eqa__( 'Used in a lesson or quiz' ) : '' ); ?>"><?php echo esc_html( eqa__( 'Delete' ) ); ?></button>
                            </form>
                        </div></td>
                    </tr>
                <?php endforeach; ?>
                </tbody>
            </table></div>
        <?php endif; ?>
    <?php endif; ?>
</div>
