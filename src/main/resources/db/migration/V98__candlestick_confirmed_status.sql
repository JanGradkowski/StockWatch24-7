-- Candlestick conditions in custom alerts get a status: CONFIRMED (the pattern's profit target was reached
-- within the window; the builder's default) or DETECTED (recognised within the window). Saved rules waited for
-- detection unless "confirmedOnly" was ticked; all of them now wait for confirmation.
-- Rules are stored exactly as CustomAlertDefinition.toJson writes them, so the text is edited in place to read
-- as it writes the new field: right after the family, with no "confirmedOnly". An unchanged rule saved again
-- in the builder then still compares equal and keeps its revision.
-- A changed rule starts again from the next candle for every stock, as an edit in the builder does
-- (CustomAlertService.update); the trigger history keeps the old rule text.

with changed as (
    update custom_alerts
    set definition = replace(regexp_replace(definition,
            '("kind":"PATTERN","negate":(true|false),"family":"CANDLESTICK",)("direction":)', '\1"status":"CONFIRMED",\3', 'g'),
            ',"confirmedOnly":true', ''),
        revision = revision + 1
    -- Only conditions without a status yet (the direction follows the family directly).
    where definition like '%"family":"CANDLESTICK","direction":%'
    returning id, revision
)
update custom_alert_states s
set revision = c.revision, last_evaluated_timestamp = null, last_result = null, status = 'OK', status_detail = null,
    triggered_once = false, notify_on_baseline = false, updated_at = current_timestamp
from changed c
where s.alert_id = c.id;
