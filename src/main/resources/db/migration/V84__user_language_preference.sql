-- Interface and email language. preferred_language is the user's explicit choice (null = follow the
-- browser); detected_language is the browser language last seen, used for emails sent in the background.
alter table users add column preferred_language varchar(8);
alter table users add column detected_language varchar(8);
alter table users add constraint ck_users_preferred_language check (preferred_language is null or preferred_language in ('en', 'pl'));
alter table users add constraint ck_users_detected_language check (detected_language is null or detected_language in ('en', 'pl'));
