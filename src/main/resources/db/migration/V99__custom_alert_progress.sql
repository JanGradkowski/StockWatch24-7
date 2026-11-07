-- A stock part-way through a sequence ("in order" group) on its latest checked candle: how many steps are done, how
-- many the sequence has and how many candles the next step has left (JSON), for "halfway" on the alert's pages.
-- Null when no sequence is under way.
alter table custom_alert_states add column if not exists progress varchar(160);
