-- Quiet hours: between 22:00 and 07:00 a channel's messages still arrive, but silently (no sound or vibration).
-- The daily check runs at midnight Brussels time, so this is when most alerts land.
alter table alert_channels add column quiet_hours boolean not null default false;
