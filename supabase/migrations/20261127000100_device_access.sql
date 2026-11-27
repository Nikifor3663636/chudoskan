-- Enrollment decisions apply to the attested key for one account and purpose.
create table app.device_enrollments (
  id uuid primary key default gen_random_uuid(),
  uid text not null references public.users(uid) on delete cascade,
  purpose smallint not null check (purpose between 1 and 3),
  public_key bytea not null check (octet_length(public_key) = 65 and get_byte(public_key, 0) = 4),
  status text not null default 'pending' check (status in ('pending', 'approved', 'blocked', 'replaced')),
  requested_at timestamptz not null default statement_timestamp(),
  request_ip inet,
  unique(uid, purpose, public_key)
);
create unique index device_enrollments_pending on app.device_enrollments(uid, purpose) where status = 'pending';

alter table app.device_bindings drop constraint device_bindings_pkey;
alter table app.device_bindings add primary key (uid, purpose);
insert into app.device_enrollments(uid, purpose, public_key, status)
select b.uid, b.purpose, b.public_key, 'approved' from app.device_bindings b join public.users u using (uid);

revoke all on app.device_enrollments from public, anon, authenticated, service_role;
-- Enrollment can only mutate a binding through an administrator decision.
revoke execute on function app.bind_device(text, integer, bytea) from service_role;

-- The Rust gateway verifies identity and hardware attestation before calling.
create function app.request_device_enrollment(p_uid text, p_purpose integer, p_key bytea, p_ip inet)
returns text language plpgsql security definer set search_path = '' as $$
declare decision text;
begin
  perform 1 from public.users where uid = p_uid and approved for update;
  if not found then return 'unavailable'; end if;

  select status into decision from app.device_enrollments
  where uid = p_uid and purpose = p_purpose and public_key = p_key;
  if decision in ('blocked', 'pending') then return decision; end if;
  if exists (select 1 from app.device_bindings where uid = p_uid and purpose = p_purpose and public_key = p_key) then
    return 'approved';
  end if;
  if exists (select 1 from app.device_enrollments where uid = p_uid and purpose = p_purpose and status = 'pending') then
    return 'another_pending';
  end if;

  insert into app.device_enrollments(uid, purpose, public_key, request_ip)
  values (p_uid, p_purpose, p_key, p_ip)
  on conflict (uid, purpose, public_key) do update
    set status = 'pending', id = gen_random_uuid(), requested_at = statement_timestamp(), request_ip = excluded.request_ip;
  insert into public.security_events(by_uid, action, target, ip, outcome)
  values (p_uid, 'session.denied', p_uid, p_ip, 'awaiting_device_approval');
  return 'pending';
end
$$;

create view public.device_access with (security_barrier = true) as
select e.id, e.uid, u.name, e.purpose, e.status, e.requested_at,
  host(e.request_ip) as request_ip, host(s.ip) as last_ip, s.seen_at
from app.device_enrollments e
join public.users u on u.uid = e.uid
left join app.device_bindings b on b.uid = e.uid and b.purpose = e.purpose and b.public_key = e.public_key
left join lateral (
  select ip, at as seen_at from public.security_events
  where action = 'session.allowed' and by_uid = e.uid
    and outcome = 'device_verified:' || b.generation::text
  order by id desc limit 1
) s on true
where app.firebase_ok() and app.is_super();

revoke all on public.device_access from public, anon, authenticated;
grant select on public.device_access to authenticated;

create function public.decide_device_access(p_id uuid, p_action text)
returns boolean language plpgsql security definer set search_path = '' as $$
declare target app.device_enrollments%rowtype;
begin
  if not (app.firebase_ok() and app.is_super()) then return false; end if;
  select * into target from app.device_enrollments where id = p_id;
  if not found then return false; end if;
  perform 1 from public.users where uid = target.uid for update;
  select * into target from app.device_enrollments where id = p_id for update;
  if not found then return false; end if;

  if p_action = 'approve' and target.status = 'pending' then
    if not exists (select 1 from public.users where uid = target.uid and approved) then return false; end if;
    update app.device_enrollments set status = 'replaced'
    where uid = target.uid and purpose = target.purpose and status = 'approved';
    delete from app.device_bindings where uid = target.uid and purpose = target.purpose;
    insert into app.device_bindings(uid, purpose, public_key) values (target.uid, target.purpose, target.public_key);
    update app.device_enrollments set status = 'approved' where id = p_id;
  elsif p_action = 'block' and target.status in ('approved', 'pending') then
    delete from app.device_bindings where uid = target.uid and purpose = target.purpose and public_key = target.public_key;
    update app.device_enrollments set status = 'blocked' where id = p_id;
  else
    return false;
  end if;

  insert into public.security_events(by_uid, action, target, outcome)
  values (app.uid(), case when p_action = 'block' then 'session.revoked' else 'session.allowed' end,
    target.uid, case when p_action = 'block' then 'device_key_blocked' else 'device_key_approved' end);
  return true;
end
$$;

create index security_events_device_session on public.security_events(by_uid, outcome, id desc)
where action = 'session.allowed';

create or replace function app.finish_device_challenge(p_uid text, p_purpose integer, p_nonce bytea, p_observed_ip inet)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  binding_generation uuid;
  new_session uuid;
begin
  select generation into binding_generation from app.device_bindings
    where uid = p_uid and purpose = p_purpose for update;
  if not found then return null; end if;
  delete from app.device_challenges where nonce = p_nonce and generation = binding_generation
    and expires_at > extract(epoch from clock_timestamp());
  if not found then return null; end if;
  delete from app.device_sessions where generation = binding_generation and expires_at <= clock_timestamp();
  insert into app.device_sessions(generation, expires_at)
  values (binding_generation, clock_timestamp() + interval '5 minutes') returning id into new_session;
  insert into public.security_events(by_uid, action, target, ip, outcome)
  values (p_uid, 'session.allowed', p_uid, p_observed_ip, 'device_verified:' || binding_generation::text);
  return new_session;
end
$$;

create or replace function app.device_session_valid(p_uid text, p_purpose integer, p_session uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from app.device_sessions s join app.device_bindings b using (generation)
    join public.users u on u.uid = b.uid and u.approved
    where s.id = p_session and b.uid = p_uid and b.purpose = p_purpose
      and s.expires_at > statement_timestamp()
  )
$$;

revoke all on function app.request_device_enrollment(text, integer, bytea, inet) from public, anon, authenticated;
grant execute on function app.request_device_enrollment(text, integer, bytea, inet) to service_role;
revoke all on function public.decide_device_access(uuid, text) from public, anon;
grant execute on function public.decide_device_access(uuid, text) to authenticated;
