-- Activate per account after enrolling a test device. Keep separate accounts for
-- browser/desktop access until those clients support a device session.
create table app.device_required (
  uid text primary key references public.users(uid) on delete cascade
);
revoke all on app.device_required from public, anon, authenticated, service_role, chudoscan_device_gateway;

create function app.firebase_identity_ok() returns boolean
language sql stable set search_path = '' as $$
  select coalesce(auth.jwt() ->> 'iss', '') = 'https://securetoken.google.com/chudoostriv'
     and coalesce(auth.jwt() ->> 'aud', '') = 'chudoostriv'
     and app.uid() is not null
$$;
revoke execute on function app.firebase_identity_ok() from public, anon, authenticated;

create or replace function app.firebase_ok() returns boolean
language plpgsql stable security definer set search_path = '' as $$
declare
  subject text := app.uid();
  session_header text;
begin
  if not app.firebase_identity_ok() then return false; end if;
  if not exists (select 1 from app.device_required where uid = subject) then return true; end if;

  session_header := nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-device-session';
  if session_header is null or session_header !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    return false;
  end if;
  return app.device_session_valid(subject, 1, session_header::uuid);
end
$$;

-- Only the caller's own profile is needed before device approval. Direct table
-- access, employee lists, writes, and stock remain behind firebase_ok.
create function public.device_login_profile() returns setof public.users
language sql stable security definer set search_path = '' as $$
  select u.* from public.users u where u.uid = app.uid() and app.firebase_identity_ok()
$$;
revoke all on function public.device_login_profile() from public, anon;
grant execute on function public.device_login_profile() to authenticated;

-- acting_shop uses a private profile helper and therefore needs an explicit
-- guard in addition to guards on the tables it is normally used with.
create or replace function public.acting_shop() returns text
language sql stable set search_path = '' as $$
  select case when app.firebase_ok() then app.acting_shop() end
$$;
