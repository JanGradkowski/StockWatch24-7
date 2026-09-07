alter table email_outbox add column receipt varchar(16) not null default 'INITIAL';
alter table email_outbox add constraint email_outbox_receipt_check check (receipt in ('INITIAL', 'FOLLOW_UP'));
-- Spring Data's IgnoreCase derived queries use upper(), so match the actual expression.
create index if not exists users_email_upper_idx on users(upper(email));
drop index if exists users_email_lower_idx;
