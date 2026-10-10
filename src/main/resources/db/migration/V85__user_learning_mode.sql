-- Learning mode shows "?" explanations beside options and underlines technical terms. Off by default.
alter table users add column learning_mode boolean not null default false;
