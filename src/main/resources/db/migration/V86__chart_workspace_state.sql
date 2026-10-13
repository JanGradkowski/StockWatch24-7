-- Chart drawings, indicator layouts and saved templates, kept per account so they follow the user
-- across devices. Payloads are JSON documents written by the chart workspace and validated by the
-- application; the database only needs to keep them per account and symbol.
create table user_chart_drawings (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    symbol varchar(32) not null,
    drawing_id varchar(64) not null,
    payload text not null,
    version integer not null default 1,
    updated_at timestamp without time zone not null default current_timestamp,
    constraint uq_user_chart_drawing unique (user_id, symbol, drawing_id)
);

create index ix_user_chart_drawings_symbol on user_chart_drawings (user_id, symbol);

create table user_chart_layouts (
    user_id bigint not null references users(id) on delete cascade,
    scope varchar(64) not null,
    payload text not null,
    version integer not null default 1,
    updated_at timestamp without time zone not null default current_timestamp,
    primary key (user_id, scope)
);

create table user_chart_templates (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    kind varchar(16) not null,
    subject varchar(64) not null,
    name varchar(80) not null,
    payload text not null,
    updated_at timestamp without time zone not null default current_timestamp,
    constraint uq_user_chart_template unique (user_id, kind, subject, name)
);

create index ix_user_chart_templates_subject on user_chart_templates (user_id, kind, subject);
