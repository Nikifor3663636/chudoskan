-- A dedicated database login for the HTTPS gateway inherits this group.
create role chudoscan_device_gateway nologin;
grant usage on schema app to chudoscan_device_gateway;
grant execute on function app.request_device_enrollment(text, integer, bytea, inet),
  app.issue_device_challenge(text, integer, bytea), app.device_challenge(text, integer, bytea),
  app.finish_device_challenge(text, integer, bytea, inet), app.device_session_valid(text, integer, uuid)
  to chudoscan_device_gateway;

create table app.android_enrollment_challenges (
  uid text not null references public.users(uid) on delete cascade,
  nonce bytea primary key check (octet_length(nonce) = 32),
  expires_at timestamptz not null
);
create index android_enrollment_challenges_user on app.android_enrollment_challenges(uid, expires_at);
revoke all on app.android_enrollment_challenges from public, anon, authenticated, service_role, chudoscan_device_gateway;

create function app.android_enrollment_status(p_uid text, p_key bytea)
returns text language sql stable security definer set search_path = '' as $$
  select case
    when not exists (select 1 from public.users where uid = p_uid and approved) then 'unavailable'
    when exists (select 1 from app.device_enrollments where uid = p_uid and purpose = 1
                 and public_key = p_key and status = 'blocked') then 'blocked'
    when exists (select 1 from app.device_bindings where uid = p_uid and purpose = 1
                 and public_key = p_key) then 'approved'
    when exists (select 1 from app.device_enrollments where uid = p_uid and purpose = 1
                 and public_key = p_key and status = 'pending') then 'pending'
    when exists (select 1 from app.device_enrollments where uid = p_uid and purpose = 1
                 and status = 'pending') then 'another_pending'
    else 'enroll'
  end
$$;

create function app.issue_android_enrollment(p_uid text, p_nonce bytea)
returns boolean language plpgsql security definer set search_path = '' as $$
begin
  perform 1 from public.users where uid = p_uid and approved for update;
  if not found then return false; end if;
  delete from app.android_enrollment_challenges
    where uid = p_uid and expires_at <= clock_timestamp();
  if (select count(*) from app.android_enrollment_challenges where uid = p_uid) >= 4 then
    return false;
  end if;
  insert into app.android_enrollment_challenges(uid, nonce, expires_at)
  values (p_uid, p_nonce, clock_timestamp() + interval '2 minutes');
  return true;
end
$$;

create function app.android_enrollment_challenge_valid(p_uid text, p_nonce bytea)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from app.android_enrollment_challenges
    where uid = p_uid and nonce = p_nonce and expires_at > statement_timestamp()
  )
$$;

create function app.consume_android_enrollment(p_uid text, p_nonce bytea)
returns boolean language plpgsql security definer set search_path = '' as $$
begin
  perform 1 from public.users where uid = p_uid and approved for update;
  if not found then return false; end if;
  delete from app.android_enrollment_challenges
    where uid = p_uid and nonce = p_nonce and expires_at > clock_timestamp();
  return found;
end
$$;

revoke all on function app.android_enrollment_status(text, bytea),
  app.issue_android_enrollment(text, bytea), app.android_enrollment_challenge_valid(text, bytea),
  app.consume_android_enrollment(text, bytea) from public, anon, authenticated, service_role;
grant execute on function app.android_enrollment_status(text, bytea),
  app.issue_android_enrollment(text, bytea), app.android_enrollment_challenge_valid(text, bytea),
  app.consume_android_enrollment(text, bytea) to chudoscan_device_gateway;
