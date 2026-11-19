-- A developing Elliott count's expected move changes direction at every stage. An account that followed both BUY
-- and SELL Elliott signals for a stock kept the count under the follow that saw it first, and the other follow
-- added its own copy at the first stage whose move went its way; both copies then advanced together, so the
-- archive showed each later stage twice. The scheduled check now leaves a count with the follow that saw it first.
-- Remove the later copies from the archive, as deleting them there would; only visible signals are compared.

-- Copies still tracking the same count as an older signal of another follow.
update alert_events copy
set deleted_at = now()
from alert_rules copy_rule
where copy_rule.id = copy.alert_rule_id
  and copy_rule.pattern_family = 'ELLIOTT_WAVE'
  and copy.deleted_at is null
  and copy.elliott_development_key is not null
  and copy.lifecycle_status = 'DETECTED'
  and exists (
      select 1
      from alert_events first
      join alert_rules first_rule on first_rule.id = first.alert_rule_id
      where first_rule.user_id = copy_rule.user_id
        and first_rule.stock_asset_id = copy_rule.stock_asset_id
        and first_rule.interval = copy_rule.interval
        and first_rule.pattern_family = 'ELLIOTT_WAVE'
        and first_rule.id <> copy_rule.id
        and first.deleted_at is null
        and first.lifecycle_status = 'DETECTED'
        and first.elliott_development_key = copy.elliott_development_key
        and first.id < copy.id);

-- Copies at the same pattern and candle as an older signal of another follow, closed ones included.
update alert_events copy
set deleted_at = now()
from alert_rules copy_rule
where copy_rule.id = copy.alert_rule_id
  and copy_rule.pattern_family = 'ELLIOTT_WAVE'
  and copy.deleted_at is null
  and copy.elliott_development_key is not null
  and exists (
      select 1
      from alert_events first
      join alert_rules first_rule on first_rule.id = first.alert_rule_id
      where first_rule.user_id = copy_rule.user_id
        and first_rule.stock_asset_id = copy_rule.stock_asset_id
        and first_rule.interval = copy_rule.interval
        and first_rule.pattern_family = 'ELLIOTT_WAVE'
        and first_rule.id <> copy_rule.id
        and first.deleted_at is null
        and first.pattern = copy.pattern
        and first.signal_candle_timestamp = copy.signal_candle_timestamp
        and first.id < copy.id);
