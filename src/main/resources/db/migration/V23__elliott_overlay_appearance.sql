alter table users
    add column if not exists elliott_motive_color varchar(7) not null default '#3B82F6';

alter table users
    add column if not exists elliott_corrective_color varchar(7) not null default '#A855F7';

alter table users drop constraint if exists ck_users_elliott_motive_color;
alter table users add constraint ck_users_elliott_motive_color
    check (elliott_motive_color ~ '^#[0-9A-F]{6}$');

alter table users drop constraint if exists ck_users_elliott_corrective_color;
alter table users add constraint ck_users_elliott_corrective_color
    check (elliott_corrective_color ~ '^#[0-9A-F]{6}$');

alter table users drop constraint if exists ck_users_elliott_colors_distinct;
alter table users add constraint ck_users_elliott_colors_distinct
    check (elliott_motive_color <> elliott_corrective_color);
