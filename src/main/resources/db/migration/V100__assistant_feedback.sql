-- Replies people marked as not helpful in the assistant panel, with the question they answered, so the
-- assistant's knowledge files can be improved. Rows exist only when someone sends one; they go with the account.
create table if not exists assistant_feedback (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    page varchar(200),
    question varchar(1000),
    reply varchar(6000) not null,
    created_at timestamptz not null default current_timestamp
);

create index if not exists idx_assistant_feedback_user on assistant_feedback (user_id);
create index if not exists idx_assistant_feedback_created on assistant_feedback (created_at);
