-- One switch to stop every alert and notification email (signals, outlook changes, chart alerts,
-- congressional and insider filings), set from the email's one-click unsubscribe link or the settings
-- page. Account and security emails are never paused.
alter table users add column notification_emails_paused boolean not null default false;
