-- ЧудоСкан: дані в Postgres, вхід — Firebase Auth (Third-Party Auth).
-- Права 1:1 з firestore.rules; тести — supabase/tests.

create extension if not exists pgcrypto;

-- Службові функції — в окремій схемі, яку PostgREST не публікує.
create schema if not exists app;
grant usage on schema app to authenticated, anon;

-- ---------- Таблиці ----------

create table public.users (
  uid         text primary key check (length(uid) between 1 and 128),
  name        text not null default '' check (length(name) <= 200),
  phone       text not null default '' check (length(phone) <= 40),
  region      text not null default '' check (length(region) <= 100),
  shop        text not null default '' check (length(shop) <= 200),
  shop_id     text not null default '' check (length(shop_id) <= 40),
  approved    boolean not null default false,
  admin       boolean not null default false,
  super_admin boolean not null default false,
  stock_admin boolean not null default false,
  approved_by text,
  changed_at  timestamptz,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index users_shop_id on public.users (shop_id);

create table public.shops (
  id              text primary key check (length(id) between 1 and 40),
  region          text not null default '' check (length(region) <= 100),
  address         text not null default '' check (length(address) <= 300),
  code1c          text check (length(code1c) <= 40),
  printer_subnets text[] not null default '{}' check (coalesce(array_length(printer_subnets, 1), 0) <= 32)
);

create table public.stores (
  code    text primary key check (code ~ '^[0-9]{1,4}$'),
  name    text not null default '' check (length(name) <= 200),
  address text not null default '' check (length(address) <= 300),
  city    text not null default '' check (length(city) <= 100),
  region  text not null default '' check (length(region) <= 100),
  shop_id text check (length(shop_id) <= 100),
  updated_at timestamptz not null default now()
);

-- q: {"<код магазину>": кількість ≥ 0}, до 100 магазинів.
create function app.q_ok(q jsonb) returns boolean
language sql immutable as $$
  select case when jsonb_typeof(q) <> 'object' then false
    else (select count(*) <= 100
                 and bool_and(case when jsonb_typeof(e.value) = 'number' then (e.value)::numeric >= 0 else false end)
                 is not false
          from jsonb_each(q) e)
  end
$$;

create table public.stock (
  barcode    text primary key check (length(barcode) between 1 and 64),
  name       text not null default '' check (length(name) <= 300),
  code       text not null default '' check (length(code) <= 40),
  article    text not null default '' check (length(article) <= 100),
  q          jsonb not null default '{}' check (app.q_ok(q)),
  updated_at timestamptz not null default now()
);
create index stock_article on public.stock (article);
create index stock_code on public.stock (code);

create table public.stock_uploads (
  id         bigserial primary key,
  at         timestamptz not null default now(),
  by_uid     text not null,
  by_name    text not null default '',
  source     text not null default 'xlsx' check (source in ('xlsx', 'web', 'csv', 'migration')),
  file_name  text not null default '' check (length(file_name) <= 300),
  rows_total int not null default 0,
  changed    int not null default 0,
  removed    int not null default 0,
  shops      text[] not null default '{}',
  status     text not null default 'open' check (status in ('open', 'done', 'failed'))
);

-- Зведення останнього успішного завантаження (замість meta/stock).
create view public.meta_stock with (security_invoker = true) as
  select id, at as updated_at, by_name as updated_by, by_uid, source, file_name,
         rows_total as items, coalesce(array_length(shops, 1), 0) as stores, changed, removed
  from public.stock_uploads
  where status = 'done'
  order by at desc, id desc
  limit 1;

create table public.printers (
  shop_id      text not null references public.shops (id) on delete cascade,
  id           text not null check (length(id) between 1 and 200),
  model        text not null check (length(model) <= 120),
  kind         text not null check (kind in ('label_40x24', 'a4')),
  name         text check (length(name) <= 40),
  ip           text not null check (length(ip) <= 45),
  port         int not null,
  transport    text not null check (transport in ('tcp', 'agent', 'usb')),
  agent_id     text,
  printer_name text check (length(printer_name) <= 200),
  last_seen    timestamptz not null default now(),
  last_ok_by   text,
  density      int,
  speed        int,
  primary key (shop_id, id)
);

create table public.devices (
  uid         text primary key,
  shop_id     text not null,
  name        text not null default '' check (length(name) <= 100),
  code        text,
  approved_by text not null,
  paired_at   timestamptz not null default now()
);
create index devices_shop_id on public.devices (shop_id);

create table public.device_pairing (
  code       text primary key check (code ~ '^[1-9][0-9]{5}$'),
  uid        text not null,
  pc_name    text not null check (length(pc_name) <= 100),
  expires_at timestamptz not null
);

create table public.agents (
  uid        text primary key,
  shop_id    text not null,
  pc_name    text not null default '' check (length(pc_name) <= 100),
  online     boolean not null default false,
  last_seen  timestamptz not null default now(),
  version    text check (length(version) <= 40),
  queue      int not null default 0,
  last_error text check (length(last_error) <= 500),
  printers   jsonb not null default '[]'
);

create table public.print_jobs (
  id         uuid primary key default gen_random_uuid(),
  shop_id    text not null,
  agent_id   text not null,
  printer    text not null check (length(printer) <= 200),
  by_uid     text not null,
  status     text not null default 'queued' check (status in ('queued', 'printing', 'done', 'error')),
  lang       text not null default 'EZPL' check (length(lang) <= 20),
  data       bytea not null check (octet_length(data) <= 900000),
  size       int not null default 0,
  error      text check (length(error) <= 500),
  attempts   int not null default 0,
  created_at timestamptz not null default now(),
  started_at timestamptz,
  done_at    timestamptz
);
create index print_jobs_agent_status on public.print_jobs (agent_id, status);

create table public.admin_log (
  id      bigserial primary key,
  at      timestamptz not null default now(),
  by_uid  text not null,
  action  text not null check (length(action) <= 100),
  target  text check (length(target) <= 200),
  shop_id text check (length(shop_id) <= 40),
  details jsonb check (details is null or length(details::text) <= 4000)
);

-- ---------- Хто робить запит ----------

-- Токен саме нашого проєкту Firebase (не чужого).
create function app.firebase_ok() returns boolean
language sql stable as $$
  select coalesce(auth.jwt() ->> 'iss', '') = 'https://securetoken.google.com/chudoostriv'
     and coalesce(auth.jwt() ->> 'aud', '') = 'chudoostriv'
$$;

create function app.uid() returns text
language sql stable as $$ select nullif(auth.jwt() ->> 'sub', '') $$;

create function app.me() returns public.users
language sql stable security definer set search_path = '' as $$
  select u from public.users u where u.uid = app.uid()
$$;

create function app.is_approved() returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select approved from public.users where uid = app.uid()), false)
$$;

create function app.is_super() returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select approved and super_admin from public.users where uid = app.uid()), false)
$$;

create function app.is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select approved and admin from public.users where uid = app.uid()), false)
$$;

create function app.is_stock_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select approved and (stock_admin or super_admin) from public.users where uid = app.uid()), false)
$$;

-- Підтверджений співробітник саме цього магазину.
create function app.works_at(p_shop text) returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select u.approved and u.shop_id = p_shop from public.users u where u.uid = app.uid()), false)
$$;

-- Адмін цього магазину.
create function app.is_shop_admin(p_shop text) returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select u.approved and u.admin and u.shop_id = p_shop from public.users u where u.uid = app.uid()), false)
$$;

create function app.can_manage(p_shop text) returns boolean
language sql stable as $$ select app.is_shop_admin(p_shop) or app.is_super() $$;

create function app.can_see(p_shop text) returns boolean
language sql stable as $$ select app.works_at(p_shop) or app.is_super() $$;

-- Магазин підключеного ПК (devices.uid = uid входу), інакше null.
create function app.device_shop() returns text
language sql stable security definer set search_path = '' as $$
  select shop_id from public.devices where uid = app.uid()
$$;

-- «Працювати як у магазині ЧО NN»: superAdmin передає заголовок x-act-shop; users.shop_id не змінюється.
create function app.acting_shop() returns text
language sql stable as $$
  select case
    when app.is_super() then coalesce(
      nullif((current_setting('request.headers', true))::json ->> 'x-act-shop', ''),
      (app.me()).shop_id)
    else (app.me()).shop_id
  end
$$;
create function public.acting_shop() returns text
language sql stable as $$ select app.acting_shop() $$;

-- Телефон в анкеті — номер, під яким увійшли (логін 380…@tel.chudoostriv.app або phone_number з токена).
create function app.phone_ok(p text) returns boolean
language sql stable as $$
  select p is null or p = ''
      or p = coalesce(auth.jwt() ->> 'phone_number', '')
      or coalesce(auth.jwt() ->> 'email', '') = replace(p, '+', '') || '@tel.chudoostriv.app'
$$;

grant execute on all functions in schema app to authenticated;

alter table public.print_jobs alter column by_uid set default app.uid();
alter table public.admin_log alter column by_uid set default app.uid();

-- ---------- Доступ: таблиці лише через RLS; anon — нічого ----------

revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;

do $$
declare t text;
begin
  foreach t in array array['users','shops','stores','stock','stock_uploads','printers','devices',
                           'device_pairing','agents','print_jobs','admin_log'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format($p$create policy firebase_only on public.%I as restrictive for all to authenticated
                      using (app.firebase_ok()) with check (app.firebase_ok())$p$, t);
  end loop;
end $$;

-- users
grant select on public.users to authenticated;
grant insert (uid, name, phone, region, shop, shop_id) on public.users to authenticated;
grant update (name, phone, region, shop, shop_id, approved, admin, approved_by, changed_at) on public.users to authenticated;

create policy users_select on public.users for select to authenticated
  using (uid = app.uid() or app.is_shop_admin(shop_id) or app.is_super());
create policy users_insert on public.users for insert to authenticated
  with check (uid = app.uid() and approved = false and admin = false
              and super_admin = false and stock_admin = false and app.phone_ok(phone));
create policy users_update on public.users for update to authenticated
  using (uid = app.uid() or app.can_manage(shop_id))
  with check (true);

-- Поля, які змінює кожен (RLS не бачить старий рядок — перевіряє тригер).
create function app.users_guard() returns trigger
language plpgsql set search_path = '' as $$
declare me text := app.uid();
begin
  if current_user not in ('authenticated', 'anon') then
    new.updated_at := now();
    return new;
  end if;
  if new.super_admin is distinct from old.super_admin or new.stock_admin is distinct from old.stock_admin
     or new.uid is distinct from old.uid or new.created_at is distinct from old.created_at then
    raise exception 'roles are changed only in Dashboard' using errcode = '42501';
  end if;
  if old.uid = me then
    if not app.phone_ok(new.phone) then
      raise exception 'phone must match login' using errcode = '42501';
    end if;
    if old.approved = false and new.approved = false then
      if new.admin is distinct from old.admin or new.approved_by is distinct from old.approved_by
         or new.changed_at is distinct from old.changed_at then
        raise exception 'only own profile fields' using errcode = '42501';
      end if;
    elsif (new.approved, new.admin, new.region, new.shop, new.shop_id, new.approved_by, new.changed_at)
          is distinct from
          (old.approved, old.admin, old.region, old.shop, old.shop_id, old.approved_by, old.changed_at) then
      raise exception 'after approval only name and phone' using errcode = '42501';
    end if;
  else
    if not app.can_manage(old.shop_id) then
      raise exception 'not your shop' using errcode = '42501';
    end if;
    if (new.name, new.phone) is distinct from (old.name, old.phone) then
      raise exception 'admin does not change name or phone' using errcode = '42501';
    end if;
    if new.shop_id is distinct from old.shop_id and not app.is_super() then
      raise exception 'shop admin does not move to another shop' using errcode = '42501';
    end if;
    if new.approved_by is distinct from old.approved_by and new.approved_by is distinct from me then
      raise exception 'approved_by must be you' using errcode = '42501';
    end if;
    if new.changed_at is distinct from old.changed_at then
      new.changed_at := now();
    end if;
  end if;
  new.updated_at := now();
  return new;
end $$;
create trigger users_guard before update on public.users for each row execute function app.users_guard();

-- shops
grant select on public.shops to authenticated;
grant insert (id, region, address, code1c, printer_subnets) on public.shops to authenticated;
grant update (region, address, code1c, printer_subnets) on public.shops to authenticated;

create policy shops_select on public.shops for select to authenticated
  using (app.can_see(id) or app.is_stock_admin());
create policy shops_insert on public.shops for insert to authenticated
  with check (app.can_see(id) or app.is_stock_admin());
create policy shops_update on public.shops for update to authenticated
  using (app.can_see(id) or app.is_stock_admin()) with check (true);

create function app.shops_guard() returns trigger
language plpgsql set search_path = '' as $$
declare
  o_region text := case when tg_op = 'UPDATE' then old.region else '' end;
  o_address text := case when tg_op = 'UPDATE' then old.address else '' end;
  o_code text := case when tg_op = 'UPDATE' then old.code1c else null end;
  o_subnets text[] := case when tg_op = 'UPDATE' then old.printer_subnets else '{}' end;
begin
  if current_user not in ('authenticated', 'anon') then return new; end if;
  if (new.region, new.address) is distinct from (o_region, o_address) and not app.is_stock_admin() then
    raise exception 'region/address: stock admin only' using errcode = '42501';
  end if;
  if new.code1c is distinct from o_code and not (app.can_manage(new.id) or app.is_stock_admin()) then
    raise exception 'code1c: shop admin or stock admin only' using errcode = '42501';
  end if;
  if new.printer_subnets is distinct from o_subnets and not app.can_see(new.id) then
    raise exception 'printer_subnets: shop staff only' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger shops_guard before insert or update on public.shops for each row execute function app.shops_guard();

-- stock, stores, stock_uploads (meta): читають підтверджені; пишуть лише RPC (етап 3).
grant select on public.stock, public.stores, public.stock_uploads, public.meta_stock to authenticated;
create policy stock_select on public.stock for select to authenticated using (app.is_approved());
create policy stores_select on public.stores for select to authenticated using (app.is_approved());
create policy stock_uploads_select on public.stock_uploads for select to authenticated using (app.is_approved());

-- printers
grant select, delete on public.printers to authenticated;
-- shop_id, id — у списку, щоб працював upsert; зміну ключа забороняє тригер.
grant insert (shop_id, id, model, kind, name, ip, port, transport, agent_id, printer_name, last_seen, last_ok_by, density, speed)
  on public.printers to authenticated;
grant update (shop_id, id, model, kind, name, ip, port, transport, agent_id, printer_name, last_seen, last_ok_by, density, speed)
  on public.printers to authenticated;

create policy printers_select on public.printers for select to authenticated using (app.can_see(shop_id));
create policy printers_insert on public.printers for insert to authenticated with check (app.can_see(shop_id));
create policy printers_update on public.printers for update to authenticated
  using (app.can_see(shop_id)) with check (app.can_see(shop_id));
create policy printers_delete on public.printers for delete to authenticated using (app.can_manage(shop_id));

create function app.printers_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user not in ('authenticated', 'anon') then return new; end if;
  if not app.can_manage(new.shop_id) then
    if tg_op = 'INSERT' and (new.density, new.speed, new.name) is distinct from (null::int, null::int, null::text) then
      raise exception 'density/speed/name: admin only' using errcode = '42501';
    end if;
    if tg_op = 'UPDATE' and (new.density, new.speed, new.name) is distinct from (old.density, old.speed, old.name) then
      raise exception 'density/speed/name: admin only' using errcode = '42501';
    end if;
  end if;
  if tg_op = 'UPDATE' and (new.shop_id, new.id) is distinct from (old.shop_id, old.id) then
    raise exception 'printer key is fixed' using errcode = '42501';
  end if;
  new.last_seen := now();
  return new;
end $$;
create trigger printers_guard before insert or update on public.printers for each row execute function app.printers_guard();

-- device_pairing: ПК створює код (до 16 хв) і бачить лише свій; адмін — через RPC device_pair.
grant select, insert, delete on public.device_pairing to authenticated;
create policy pairing_select on public.device_pairing for select to authenticated using (uid = app.uid());
create policy pairing_insert on public.device_pairing for insert to authenticated
  with check (uid = app.uid() and expires_at > now() and expires_at <= now() + interval '16 minutes');
create policy pairing_delete on public.device_pairing for delete to authenticated
  using (uid = app.uid() or app.is_admin() or app.is_super());

-- devices: бачить сам ПК і магазин; створює лише RPC device_pair; «Відключити» = delete.
grant select, delete on public.devices to authenticated;
create policy devices_select on public.devices for select to authenticated
  using (uid = app.uid() or app.can_see(shop_id));
create policy devices_delete on public.devices for delete to authenticated
  using (uid = app.uid() or app.can_manage(shop_id));

-- Адмін: що за ПК за цим кодом (лише чинний код).
create function public.device_pairing_get(p_code text) returns table (code text, pc_name text, expires_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select p.code, p.pc_name, p.expires_at from public.device_pairing p
  where p.code = p_code and p.expires_at > now()
    and app.firebase_ok() and (app.is_admin() or app.is_super())
$$;

-- Адмін підключає ПК свого магазину за чинним кодом.
create function public.device_pair(p_code text, p_shop text) returns public.devices
language plpgsql security definer set search_path = '' as $$
declare p public.device_pairing; d public.devices;
begin
  if not app.firebase_ok() or not app.can_manage(p_shop) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  select * into p from public.device_pairing where code = p_code and expires_at > now() for update;
  if not found then
    raise exception 'code expired or unknown' using errcode = 'P0002';
  end if;
  insert into public.devices (uid, shop_id, name, code, approved_by)
  values (p.uid, p_shop, p.pc_name, p.code, app.uid())
  returning * into d;
  delete from public.device_pairing where code = p_code;
  insert into public.admin_log (by_uid, action, target, shop_id) values (app.uid(), 'devicePair', p.uid, p_shop);
  return d;
end $$;

-- agents: пише себе сам підключений ПК.
grant select, insert, update, delete on public.agents to authenticated;
create policy agents_select on public.agents for select to authenticated
  using (uid = app.uid() or app.can_see(shop_id));
create policy agents_insert on public.agents for insert to authenticated
  with check (uid = app.uid() and shop_id = app.device_shop());
create policy agents_update on public.agents for update to authenticated
  using (uid = app.uid() and shop_id = app.device_shop())
  with check (uid = app.uid() and shop_id = app.device_shop());
create policy agents_delete on public.agents for delete to authenticated
  using (uid = app.uid() and shop_id = app.device_shop());

-- print_jobs
grant select, delete on public.print_jobs to authenticated;
grant insert (shop_id, agent_id, printer, by_uid, lang, data, size) on public.print_jobs to authenticated;
grant update (status, started_at, attempts, done_at, error) on public.print_jobs to authenticated;

create policy jobs_select on public.print_jobs for select to authenticated
  using (by_uid = app.uid() or agent_id = app.uid());
create policy jobs_update on public.print_jobs for update to authenticated
  using (agent_id = app.uid() and shop_id = app.device_shop())
  with check (agent_id = app.uid() and shop_id = app.device_shop());
create policy jobs_delete on public.print_jobs for delete to authenticated
  using (agent_id = app.uid() and shop_id = app.device_shop() and status in ('done', 'error'));

-- devices читається в jobs_insert від імені співробітника — через security definer.
create function app.device_in_shop(p_agent text, p_shop text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.devices d where d.uid = p_agent and d.shop_id = p_shop)
$$;
create policy jobs_insert on public.print_jobs for insert to authenticated
  with check (app.can_see(shop_id) and by_uid = app.uid() and status = 'queued'
              and app.device_in_shop(agent_id, shop_id));

-- admin_log: пишуть адміни від свого імені (stockAdmin — лише stockImport); читає superAdmin.
grant select on public.admin_log to authenticated;
grant insert (by_uid, action, target, shop_id, details) on public.admin_log to authenticated;
grant usage on sequence public.admin_log_id_seq to authenticated;
create policy log_insert on public.admin_log for insert to authenticated
  with check (by_uid = app.uid()
              and (app.is_admin() or app.is_super() or (app.is_stock_admin() and action = 'stockImport')));
create policy log_select on public.admin_log for select to authenticated using (app.is_super());

grant execute on function public.device_pairing_get(text), public.device_pair(text, text), public.acting_shop() to authenticated;
revoke execute on function public.device_pairing_get(text), public.device_pair(text, text), public.acting_shop() from anon, public;

-- ---------- Realtime: нові анкети для адміна, черга друку, підключення ПК ----------
alter publication supabase_realtime add table public.users, public.print_jobs, public.devices, public.agents;
