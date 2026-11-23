-- Browser push devices belong to signed-in sessions. Whenever an account's security version changes (a password change
-- or reset, MFA on or off, "sign out other sessions", a scheduled deletion), its push devices are removed, so a lost or
-- stolen device stops getting alerts. The device the owner is using subscribes again on its next page load, because
-- the page carries the new version.
create function remove_push_devices_on_security_change() returns trigger language plpgsql as $$
begin
    if new.security_version is distinct from old.security_version then
        delete from alert_channels where user_id = new.id and channel_type = 'PUSH';
    end if;
    return null;
end $$;

create trigger users_security_version_push_devices after update of security_version on users
    for each row execute function remove_push_devices_on_security_change();
