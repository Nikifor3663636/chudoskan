-- Запити на скидання пароля: співробітник без входу лишає номер і магазин,
-- адмін магазину бачить запит у «Мій магазин» і задає тимчасовий пароль
-- (сам пароль ставить функція reset-password; тут — лише права й облік).
--
-- Таблиця напряму анонімові закрита: створення — тільки через request_password_reset
-- (формат номера, магазин відомий, 1 запит на номер за 10 хв, не більше 30 нових на магазин за годину).
-- Відповідь однакова, чи є такий співробітник, чи ні — список номерів так не дізнатися.

create table public.password_requests (
  id         uuid primary key default gen_random_uuid(),
  phone      text not null check (phone ~ '^\+380[0-9]{9}$'),
  shop_id    text not null check (length(shop_id) between 1 and 40),
  created_at timestamptz not null default now(),
  status     text not null default 'new' check (status in ('new', 'done', 'rejected')),
  done_by    text check (length(done_by) <= 128),
  done_at    timestamptz
);
create index password_requests_shop on public.password_requests (shop_id, status, created_at desc);
create index password_requests_phone on public.password_requests (phone, created_at desc);

alter table public.password_requests enable row level security;
create policy firebase_only on public.password_requests as restrictive for all to authenticated
  using (app.firebase_ok()) with check (app.firebase_ok());

revoke all on public.password_requests from public, anon, authenticated;
grant select on public.password_requests to authenticated;
grant update (status, done_by, done_at) on public.password_requests to authenticated;

create policy password_requests_select on public.password_requests for select to authenticated
  using (app.can_manage(shop_id));
-- Адмін може лише відхилити запит свого магазину; «done» ставить reset-password.
create policy password_requests_reject on public.password_requests for update to authenticated
  using (app.can_manage(shop_id) and status = 'new')
  with check (status = 'rejected' and done_by = app.uid());

-- ---------- Створення (без входу) ----------

create function public.request_password_reset(p_phone text, p_shop text) returns text
language plpgsql volatile security definer set search_path = '' as $$
begin
  if p_phone is null or p_phone !~ '^\+380[0-9]{9}$' then return 'bad_phone'; end if;
  -- Магазин відомий: є в shops або в ньому хтось зареєстрований (id — регіон_хеш адреси, як в анкеті).
  if p_shop is null or length(p_shop) > 40
     or not (exists (select 1 from public.shops s where s.id = p_shop)
             or exists (select 1 from public.users u where u.shop_id = p_shop)) then
    return 'bad_shop';
  end if;
  -- Однакові запити з кількох вкладок або подвійний тап — не спамимо адміна.
  perform pg_advisory_xact_lock(hashtext('password_request:' || p_phone));
  if exists (select 1 from public.password_requests r
             where r.phone = p_phone and r.created_at > now() - interval '10 minutes') then
    return 'too_soon';
  end if;
  if (select count(*) from public.password_requests r
      where r.shop_id = p_shop and r.status = 'new' and r.created_at > now() - interval '1 hour') >= 30 then
    return 'too_many';
  end if;
  insert into public.password_requests (phone, shop_id) values (p_phone, p_shop);
  return 'ok';
end
$$;
revoke all on function public.request_password_reset(text, text) from public;
grant execute on function public.request_password_reset(text, text) to anon, authenticated;

-- ---------- Список для «Мій магазин» ----------

-- Нові запити магазину з іменем співробітника (якщо номер і магазин збігаються з анкетою).
create function public.password_requests_for(p_shop text)
returns table (id uuid, phone text, created_at timestamptz, uid text, name text)
language sql stable security definer set search_path = '' as $$
  select r.id, r.phone, r.created_at, u.uid, u.name
  from public.password_requests r
  left join public.users u on u.phone = r.phone and u.shop_id = r.shop_id
  where app.firebase_ok() and app.can_manage(p_shop)
    and r.shop_id = p_shop and r.status = 'new'
  order by r.created_at desc
  limit 50
$$;
revoke all on function public.password_requests_for(text) from public, anon;
grant execute on function public.password_requests_for(text) to authenticated;

-- ---------- Скидання (викликає reset-password від імені адміна) ----------

-- Кого скидаємо: лише співробітник із тим самим номером і саме цього магазину.
-- Адмін магазину — лише свого; адмін мережі — будь-якого. Адмінів мережі тут не скидають.
create function public.password_reset_target(p_id uuid) returns text
language plpgsql stable security definer set search_path = '' as $$
declare
  r public.password_requests;
  target text;
begin
  if not app.firebase_ok() then raise exception 'not allowed' using errcode = '42501'; end if;
  select * into r from public.password_requests where id = p_id;
  if r.id is null or not app.can_manage(r.shop_id) then raise exception 'not allowed' using errcode = '42501'; end if;
  if r.status <> 'new' then raise exception 'request is closed' using errcode = 'P0001'; end if;
  select u.uid into target from public.users u
   where u.phone = r.phone and u.shop_id = r.shop_id and not u.super_admin
   order by u.updated_at desc limit 1;
  if target is null then raise exception 'no such employee in this shop' using errcode = 'P0002'; end if;
  return target;
end
$$;
revoke all on function public.password_reset_target(uuid) from public, anon;
grant execute on function public.password_reset_target(uuid) to authenticated;

-- Після вдалого скидання: закрити запит і записати в журнал дій.
create function public.password_reset_finish(p_id uuid) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare v_target text := public.password_reset_target(p_id);
begin
  update public.password_requests set status = 'done', done_by = app.uid(), done_at = now() where id = p_id;
  insert into public.admin_log (by_uid, action, target, shop_id)
    select app.uid(), 'passwordReset', v_target, r.shop_id from public.password_requests r where r.id = p_id;
end
$$;
revoke all on function public.password_reset_finish(uuid) from public, anon;
grant execute on function public.password_reset_finish(uuid) to authenticated;
