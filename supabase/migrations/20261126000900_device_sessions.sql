-- Private state for the device gateway. Existing clients do not use it until
-- they authenticate through the gateway and the API requires its sessions.
create table app.device_bindings (
  uid text primary key check (octet_length(uid) between 1 and 128),
  generation uuid not null unique default gen_random_uuid(),
  purpose smallint not null check (purpose between 1 and 3),
  public_key bytea not null check (octet_length(public_key) = 65 and get_byte(public_key, 0) = 4)
);

create table app.device_challenges (
  nonce bytea primary key check (octet_length(nonce) = 32),
  generation uuid not null references app.device_bindings(generation) on delete cascade,
  expires_at bigint not null
);
create index device_challenges_binding on app.device_challenges(generation, expires_at);

create table app.device_sessions (
  id uuid primary key default gen_random_uuid(),
  generation uuid not null references app.device_bindings(generation) on delete cascade,
  expires_at timestamptz not null
);
create index device_sessions_binding on app.device_sessions(generation, expires_at);

revoke all on app.device_bindings, app.device_challenges, app.device_sessions
  from public, anon, authenticated, service_role;

-- Call only after the gateway verifies the account, enrollment authorization
-- and the hardware attestation for this exact key and enrollment challenge.
create function app.bind_device(p_uid text, p_purpose integer, p_key bytea) returns boolean
language plpgsql security definer set search_path = '' as $$
begin
  insert into app.device_bindings(uid, purpose, public_key)
  values (p_uid, p_purpose, p_key) on conflict (uid) do nothing;
  return found;
end
$$;

-- The gateway supplies a 32-byte cryptographically random nonce. The returned
-- expiry goes into the bytes the device signs, using the database clock.
create function app.issue_device_challenge(p_uid text, p_purpose integer, p_nonce bytea) returns bigint
language plpgsql security definer set search_path = '' as $$
declare
  bound app.device_bindings%rowtype;
  deadline bigint := floor(extract(epoch from clock_timestamp()))::bigint + 120;
begin
  select * into bound from app.device_bindings
    where uid = p_uid and purpose = p_purpose for update;
  if not found then return null; end if;
  delete from app.device_challenges where generation = bound.generation and expires_at <= deadline - 120;
  -- Bound outstanding work even if an account repeatedly requests challenges.
  if (select count(*) from app.device_challenges where generation = bound.generation) >= 8 then
    return null;
  end if;
  insert into app.device_challenges(nonce, generation, expires_at)
  values (p_nonce, bound.generation, deadline);
  return deadline;
end
$$;

create function app.device_challenge(p_uid text, p_purpose integer, p_nonce bytea)
returns table(public_key bytea, expires_at bigint)
language sql stable security definer set search_path = '' as $$
  select b.public_key, c.expires_at from app.device_bindings b
  join app.device_challenges c using (generation)
  where b.uid = p_uid and b.purpose = p_purpose and c.nonce = p_nonce
    and c.expires_at > extract(epoch from statement_timestamp())
$$;

-- The gateway calls this only after signature verification against the
-- server-stored key and challenge. The nonce is consumed in the same
-- transaction that grants the session, so concurrent replays cannot win.
create function app.finish_device_challenge(p_uid text, p_purpose integer, p_nonce bytea, p_observed_ip inet)
returns uuid
language plpgsql security definer set search_path = '' as $$
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
  values (p_uid, 'session.allowed', p_uid, p_observed_ip, 'device_verified');
  return new_session;
end
$$;

create function app.device_session_valid(p_uid text, p_purpose integer, p_session uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from app.device_sessions s join app.device_bindings b using (generation)
    where s.id = p_session and b.uid = p_uid and b.purpose = p_purpose
      and s.expires_at > statement_timestamp()
  )
$$;

-- Requires an active device session for an approved network administrator.
-- Removing a binding invalidates all its server sessions and pending proofs.
create function app.revoke_device(p_actor text, p_admin_session uuid, p_target text, p_observed_ip inet)
returns boolean
language plpgsql security definer set search_path = '' as $$
begin
  if not app.device_session_valid(p_actor, 2, p_admin_session) or not exists (
    select 1 from public.users where uid = p_actor and approved and super_admin
  ) then
    return false;
  end if;

  delete from app.device_bindings where uid = p_target;
  if not found then return false; end if;
  insert into public.security_events(by_uid, action, target, ip, outcome)
  values (p_actor, 'session.revoked', p_target, p_observed_ip, 'device_binding_removed');
  return true;
end
$$;

revoke all on function app.bind_device(text, integer, bytea),
  app.issue_device_challenge(text, integer, bytea), app.device_challenge(text, integer, bytea),
  app.finish_device_challenge(text, integer, bytea, inet), app.device_session_valid(text, integer, uuid),
  app.revoke_device(text, uuid, text, inet) from public, anon, authenticated;

grant usage on schema app to service_role;
grant execute on function app.bind_device(text, integer, bytea),
  app.issue_device_challenge(text, integer, bytea), app.device_challenge(text, integer, bytea),
  app.finish_device_challenge(text, integer, bytea, inet), app.device_session_valid(text, integer, uuid),
  app.revoke_device(text, uuid, text, inet) to service_role;
