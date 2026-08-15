create table user_signal_scoring_preferences (
    id bigserial primary key,
    user_id bigint not null,
    profile_version varchar(32) not null,
    preferences_payload text not null,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_user_signal_scoring_preferences_user unique (user_id),
    constraint fk_user_signal_scoring_preferences_user
        foreign key (user_id) references users(id) on delete cascade
);
