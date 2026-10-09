-- Open developing Wave III counts were scored before the compressed Wave IV guideline existed.
-- When less than the common 23.6% retracement of Wave III remains before Wave I territory, the
-- count stays valid but loses the same confidence the detector now applies to new counts.
create temporary table compressed_wave_four_counts on commit drop as
with pivot_values as (
    select ae.id,
           max(case when split_part(point.line, '|', 1) = 'I'
                    then split_part(point.line, '|', 3)::double precision end) as wave_1,
           max(case when split_part(point.line, '|', 1) = 'II'
                    then split_part(point.line, '|', 3)::double precision end) as wave_2,
           max(case when split_part(point.line, '|', 1) = 'III'
                    then split_part(point.line, '|', 3)::double precision end) as wave_3
    from alert_events ae
    cross join lateral regexp_split_to_table(ae.elliott_structure_snapshot, E'\\n') as point(line)
    where ae.elliott_signal_stage = 'WAVE_III_END'
      and ae.score_version = 'ELLIOTT_DEVELOPING_V1'
      and ae.lifecycle_status = 'DETECTED'
      and ae.deleted_at is null
      and ae.confidence_score is not null
      and ae.elliott_structure_snapshot is not null
      and coalesce(ae.confidence_reasons, '') not like '%Compressed Wave IV:%'
    group by ae.id
), rooms as (
    select pivots.*,
           (pivots.wave_3 - pivots.wave_1) / (pivots.wave_3 - pivots.wave_2) as room
    from pivot_values pivots
    where pivots.wave_1 > 0.0 and pivots.wave_2 > 0.0 and pivots.wave_3 > 0.0
      and pivots.wave_3 <> pivots.wave_2
)
select rooms.*,
       6 + round((least(1.0, (0.236 - rooms.room) / 0.236) * 4.0)::numeric)::integer as penalty,
       to_char(rooms.room * 100, 'FM990.0') as room_percent,
       to_char(rooms.wave_1, case when rooms.wave_1 >= 1 then 'FM999999999990.00'
                                  else 'FM999999999990.0000' end) as wave_1_text
from rooms
where rooms.room > 0.0 and rooms.room < 0.236;

update alert_events ae
set confidence_score = greatest(0, ae.confidence_score - counts.penalty),
    factory_confidence_score = case when ae.factory_confidence_score is null then null
                                    else greatest(0, ae.factory_confidence_score - counts.penalty) end,
    signal_strength = case
        when ae.confidence_score - counts.penalty >= 85 then 'HIGH_CONFIDENCE'
        when ae.confidence_score - counts.penalty >= 75 then 'MEDIUM_CONFIDENCE'
        when ae.confidence_score - counts.penalty > 0 then 'LOW_CONFIDENCE'
        else 'WEAK_IGNORE' end,
    confidence_reasons = concat_ws(E'\n', nullif(ae.confidence_reasons, ''),
        'Compressed Wave IV: only ' || counts.room_percent
            || '% of Wave III remains before Wave I territory at ' || counts.wave_1_text
            || ', less than the 23.6% common retracement. The count stays valid until Wave I is overlapped; '
            || 'confidence reduced by ' || counts.penalty || ' points.'),
    elliott_forecast_label = 'Projected compressed Wave IV (must stay outside Wave I territory)',
    trade_qualification = case
        when ae.elliott_trade_plan_status = 'PROJECTION_ONLY'
             and ae.trade_qualification = 'Projection only: no valid target within the standard Elliott structural boundaries.'
        then 'Projection only: Wave IV has ' || counts.room_percent
             || '% of Wave III left before Wave I territory at ' || counts.wave_1_text
             || '; no standard Fibonacci Wave IV target fits, so no trade plan is offered.'
        else ae.trade_qualification end
from compressed_wave_four_counts counts
where ae.id = counts.id;
