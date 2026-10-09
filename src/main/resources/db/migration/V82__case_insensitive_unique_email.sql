-- Sign-in and recovery look accounts up without regard to case, so two accounts whose emails differ
-- only in case would be ambiguous. Emails are already stored lower-case; enforce it in the database.
create unique index if not exists users_email_upper_unique on users (upper(email));
drop index if exists users_email_upper_idx;
