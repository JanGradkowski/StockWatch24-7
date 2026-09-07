create temporary table repaired_elliott_targets on commit drop as
with pivot_values as (
    select ae.id,
           ae.signal_candle_timestamp,
           ar.interval,
           ae.trade_entry_price,
           ae.stop_loss_price,
           max(case when split_part(point.line, '|', 1) = '0'
                    then split_part(point.line, '|', 3)::double precision end) as wave_0,
           max(case when split_part(point.line, '|', 1) = 'I'
                    then split_part(point.line, '|', 3)::double precision end) as wave_1,
           max(case when split_part(point.line, '|', 1) = 'II'
                    then split_part(point.line, '|', 3)::double precision end) as wave_2
    from alert_events ae
    join alert_rules ar on ar.id = ae.alert_rule_id
    cross join lateral regexp_split_to_table(ae.elliott_structure_snapshot, E'\\n') as point(line)
    where ae.pattern = 'ELLIOTT_BEARISH_WAVE_II_END'
      and ae.elliott_signal_stage = 'WAVE_II_END'
      and ae.trade_signal = 'SELL'
      and ae.profit_target_price <= 0.0
      and ae.trade_entry_price > 0.0
      and ae.stop_loss_price > ae.trade_entry_price
      and ae.elliott_structure_snapshot is not null
    group by ae.id, ae.signal_candle_timestamp, ar.interval,
             ae.trade_entry_price, ae.stop_loss_price
), logarithmic_targets as (
    select pivots.*,
           pivots.wave_2 * power(pivots.wave_1 / pivots.wave_0, 1.618) as target_midpoint,
           case when pivots.interval = 'DAILY' then 2.0 else 3.0 end as required_reward_risk
    from pivot_values pivots
    where pivots.wave_0 > 0.0
      and pivots.wave_1 > 0.0
      and pivots.wave_2 > 0.0
      and pivots.wave_1 < pivots.wave_0
      and pivots.wave_2 - abs(pivots.wave_1 - pivots.wave_0) * 1.618 <= 0.0
      and pivots.wave_2 - abs(pivots.wave_1 - pivots.wave_0) * 2.618 <= 0.0
), qualified_targets as (
    select targets.*,
           targets.target_midpoint * 0.985 as target_zone_low,
           targets.target_midpoint * 1.015 as target_zone_high,
           (targets.trade_entry_price - targets.target_midpoint * 1.015)
               / (targets.stop_loss_price - targets.trade_entry_price) as actual_reward_risk
    from logarithmic_targets targets
    where targets.target_midpoint > 0.0
      and targets.target_midpoint * 1.015 < targets.trade_entry_price
)
select * from qualified_targets where actual_reward_risk > 0.0;

update alert_events event
set structural_stop_price = repaired.wave_0,
    confirmation_trigger_price = repaired.target_zone_high,
    profit_target_price = repaired.target_zone_high,
    reward_risk_ratio = repaired.actual_reward_risk,
    trade_plan_version = 'ELLIOTT_FIB_RR_V2',
    pre_circuit_breaker_stop_price = event.stop_loss_price,
    atr_circuit_breaker_enabled = false,
    atr_circuit_breaker_applied = false,
    atr_circuit_breaker_value = null,
    atr_circuit_breaker_period = 14,
    atr_circuit_breaker_multiplier = 0.10,
    atr_circuit_breaker_threshold_percent = null,
    elliott_target_mid_price = repaired.target_midpoint,
    elliott_target_zone_low = repaired.target_zone_low,
    elliott_target_zone_high = repaired.target_zone_high,
    elliott_target_basis = 'Logarithmic Wave III at 161.8% after both arithmetic extensions reached zero',
    elliott_required_reward_risk_ratio = repaired.required_reward_risk,
    elliott_trade_actionable = repaired.actual_reward_risk >= repaired.required_reward_risk,
    elliott_trade_plan_status = case
        when repaired.actual_reward_risk >= repaired.required_reward_risk then 'ACTIVE'
        else 'PROJECTION_ONLY'
    end,
    elliott_trade_resolution_reason = case
        when repaired.actual_reward_risk >= repaired.required_reward_risk
            then format('Meets the minimum 1:%s risk/reward requirement.', repaired.required_reward_risk::integer)
        else format('Projection only: the logarithmic target provides 1:%s, below the required 1:%s.',
                    round(repaired.actual_reward_risk::numeric, 2),
                    repaired.required_reward_risk::integer)
    end
from repaired_elliott_targets repaired
where event.id = repaired.id;

insert into elliott_stage_trade_plans (
    alert_event_id, elliott_stage, stage_revision, expected_move, status, plan_version,
    entry_timestamp, entry_price, structural_stop_price, stop_loss_price, stop_buffer,
    hard_invalidation_price, hard_invalidation_side,
    target_midpoint, target_zone_low, target_zone_high, target_trigger_price,
    target_basis, fibonacci_ratio, target_zone_percent,
    required_reward_risk_ratio, actual_reward_risk_ratio, actionable, qualification
)
select repaired.id,
       'WAVE_II_END',
       coalesce((select max(existing.stage_revision)
                 from elliott_stage_trade_plans existing
                 where existing.alert_event_id = repaired.id
                   and existing.elliott_stage = 'WAVE_II_END'), 0) + 1,
       'SELL',
       case when repaired.actual_reward_risk >= repaired.required_reward_risk
            then 'ACTIVE' else 'PROJECTION_ONLY' end,
       'ELLIOTT_FIB_RR_V2',
       repaired.signal_candle_timestamp,
       repaired.trade_entry_price,
       repaired.wave_0,
       repaired.stop_loss_price,
       repaired.stop_loss_price - repaired.wave_0,
       repaired.wave_0,
       'ABOVE',
       repaired.target_midpoint,
       repaired.target_zone_low,
       repaired.target_zone_high,
       repaired.target_zone_high,
       'Logarithmic Wave III at 161.8% after both arithmetic extensions reached zero',
       1.618,
       1.5,
       repaired.required_reward_risk,
       repaired.actual_reward_risk,
       repaired.actual_reward_risk >= repaired.required_reward_risk,
       case when repaired.actual_reward_risk >= repaired.required_reward_risk
            then format('Meets the minimum 1:%s risk/reward requirement.', repaired.required_reward_risk::integer)
            else format('Projection only: the logarithmic target provides 1:%s, below the required 1:%s.',
                        round(repaired.actual_reward_risk::numeric, 2),
                        repaired.required_reward_risk::integer)
       end
from repaired_elliott_targets repaired;

alter table alert_events
    add constraint ck_elliott_positive_profit_target check (
        trade_plan_version not in ('ELLIOTT_NEXT_WAVE_V1', 'ELLIOTT_FIB_RR_V2')
        or profit_target_price > 0.0
    );
