alter table email_outbox add column outlook_notification_id bigint
    references technical_outlook_notifications(id) on delete cascade;
alter table email_outbox add constraint email_outbox_single_notification
    check (alert_event_id is null or outlook_notification_id is null);
