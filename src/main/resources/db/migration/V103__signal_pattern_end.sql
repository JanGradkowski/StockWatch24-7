-- The candle each technical signal's pattern ends on. Signals of one account, stock, interval and pattern family
-- that point the same way, were raised on the same candle and end on the same candle are one point: the archive
-- shows them as one row and a check emails them together. AlertEvent.patternEnd derives the value for new and
-- updated signals; this fills older ones the same way: a harmonic formation's D point, the last point of an
-- Elliott count's structure, an Elliott signal's endpoint, or the signal candle (candlesticks).

alter table alert_events add column pattern_end_timestamp bigint;

update alert_events e
set pattern_end_timestamp = coalesce(e.harmonic_endpoint_timestamp, structure.last_point,
                                     e.elliott_endpoint_timestamp, e.signal_candle_timestamp)
from (
    select id,
           case when last_line ~ '^[^|]*\|[0-9]{1,18}(\||$)' then split_part(last_line, '|', 2)::bigint end last_point
    from (
        -- In PostgreSQL's regular expressions "." also matches a newline: everything up to the last line goes.
        select id, regexp_replace(btrim(elliott_structure_snapshot, E' \n\r\t'), '^.*\n', '') last_line
        from alert_events
    ) lines
) structure
where structure.id = e.id;
