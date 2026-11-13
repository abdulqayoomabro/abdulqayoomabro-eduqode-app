<?php
/**
 * EduQode App (company training): Tutor LMS's stat card, except cards about money.
 * The variables are the ones Tutor LMS passes to its own stat card template.
 */

defined( 'ABSPATH' ) || exit;

if ( EduQode_App_No_Earnings::is_money_card( (string) ( $card_title ?? '' ), (string) ( $icon ?? '' ) ) ) {
    return;
}
if ( EduQode_App_No_Earnings::$stat_card && file_exists( EduQode_App_No_Earnings::$stat_card ) ) {
    include EduQode_App_No_Earnings::$stat_card;
}
