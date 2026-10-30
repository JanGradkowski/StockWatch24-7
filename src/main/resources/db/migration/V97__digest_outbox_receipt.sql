-- A summary email (V94) goes through the outbox with its own receipt kind; the receipts of the emails it carries
-- are recorded from digest_receipts when it is delivered.
alter table email_outbox drop constraint email_outbox_receipt_check;
alter table email_outbox add constraint email_outbox_receipt_check check (receipt in ('INITIAL', 'FOLLOW_UP', 'DIGEST'));
