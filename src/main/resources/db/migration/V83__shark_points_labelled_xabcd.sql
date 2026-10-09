-- Shark formations are now labelled X-A-B-C-D like every other five-point harmonic pattern
-- (previously Carney's 0-X-A-B-C). Relabel saved Shark point snapshots so old and new signals
-- read the same: 0->X, X->A, A->B, B->C, C->D, applied from the last letter backwards so no
-- point is renamed twice. Prices and timestamps are unchanged.
update alert_events
set harmonic_points_snapshot =
        regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
            harmonic_points_snapshot,
            '(^|\n)C\|', '\1D|', 'g'),
            '(^|\n)B\|', '\1C|', 'g'),
            '(^|\n)A\|', '\1B|', 'g'),
            '(^|\n)X\|', '\1A|', 'g'),
            '(^|\n)0\|', '\1X|', 'g')
where pattern = 'HARMONIC_SHARK'
  and harmonic_points_snapshot like '0|%';

-- The Shark-only ratio lines repeated B/XA, C/AB, CD/BC and AD/XA under the old letters.
update alert_events
set harmonic_measurements_snapshot = nullif(btrim(regexp_replace(
            harmonic_measurements_snapshot,
            '(^|\n)(A_0X|AB_XA|BC_AB|C_0X_COMPLETION)\|[^\n]*', '', 'g'), E'\n'), '')
where pattern = 'HARMONIC_SHARK'
  and harmonic_measurements_snapshot ~ '(^|\n)(A_0X|AB_XA|BC_AB|C_0X_COMPLETION)\|';

-- Saved Shark stop plans described their levels in the old letters.
update alert_events
set harmonic_stop_basis = replace(harmonic_stop_basis, '1.27 OX extension', '1.27 XA extension'),
    harmonic_stop_formula = replace(harmonic_stop_formula, 'X + (0 - X) × 1.27', 'A + (X - A) × 1.27'),
    trade_qualification = replace(trade_qualification, '50% BC reaction target', '50% CD reaction target')
where pattern = 'HARMONIC_SHARK'
  and (harmonic_stop_basis like '%OX extension%'
       or harmonic_stop_formula like '%(0 - X)%'
       or trade_qualification like '%BC reaction target%');
