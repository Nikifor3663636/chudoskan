-- Завантаження залишків мережі: begin → chunk × N → commit (одна транзакція).
-- Пишуть лише stockAdmin / superAdmin; таблиці stock / stores / stock_uploads напряму не пише ніхто.

create unlogged table app.stock_staging (
  upload_id bigint not null references public.stock_uploads (id) on delete cascade,
  barcode   text not null check (length(barcode) between 1 and 64),
  name      text not null default '',
  code      text not null default '',
  article   text not null default '',
  q         jsonb not null default '{}' check (app.q_ok(q)),
  primary key (upload_id, barcode)
);
revoke all on app.stock_staging from public, anon, authenticated;

-- Сервер (вигрузка 1С, scripts/upload-stock-csv.js) — ключем service_role; людина — stockAdmin / superAdmin.
create function app.is_service() returns boolean
language sql stable as $$ select coalesce(auth.jwt() ->> 'role', '') = 'service_role' $$;

-- Хто завантажує: uid Firebase або «service:1c».
create function app.uploader() returns text
language sql stable as $$ select coalesce(app.uid(), case when app.is_service() then 'service:1c' end) $$;

create function app.require_stock_admin() returns void
language plpgsql stable as $$
begin
  if app.is_service() then return; end if;
  if not app.firebase_ok() or not app.is_stock_admin() then
    raise exception 'stock admin only' using errcode = '42501';
  end if;
end $$;

-- Новий сеанс завантаження. Старі незавершені сеанси цього ж адміна закриваються.
create function public.stock_upload_begin(p_file_name text default '', p_source text default 'xlsx')
returns bigint
language plpgsql security definer set search_path = '' as $$
declare v_id bigint;
begin
  perform app.require_stock_admin();
  update public.stock_uploads set status = 'failed' where by_uid = app.uploader() and status = 'open';
  insert into public.stock_uploads (by_uid, by_name, source, file_name)
  values (app.uploader(), coalesce((app.me()).name, case when app.is_service() then '1С' end, ''), coalesce(nullif(p_source, ''), 'xlsx'), left(coalesce(p_file_name, ''), 300))
  returning id into v_id;
  return v_id;
end $$;

-- Пачка рядків [{barcode, name, code, article, q}] (до 5 000). Той самий штрихкод — сума по магазинах.
create function public.stock_upload_chunk(p_id bigint, p_rows jsonb)
returns int
language plpgsql security definer set search_path = '' as $$
declare n int;
begin
  perform app.require_stock_admin();
  if not exists (select 1 from public.stock_uploads where id = p_id and by_uid = app.uploader() and status = 'open') then
    raise exception 'upload % is not open', p_id using errcode = 'P0002';
  end if;
  if jsonb_typeof(p_rows) <> 'array' or jsonb_array_length(p_rows) > 5000 then
    raise exception 'rows: array of at most 5000' using errcode = '22023';
  end if;

  insert into app.stock_staging as s (upload_id, barcode, name, code, article, q)
  select p_id, r.barcode, left(coalesce(r.name, ''), 300), left(coalesce(r.code, ''), 40),
         left(coalesce(r.article, ''), 100), coalesce(r.q, '{}')
  from jsonb_to_recordset(p_rows) as r(barcode text, name text, code text, article text, q jsonb)
  where coalesce(r.barcode, '') <> ''
  on conflict (upload_id, barcode) do update
    set q = (select coalesce(jsonb_object_agg(k, v), '{}') from (
               select k, sum(v::numeric) as v from (
                 select key as k, value as v from jsonb_each(s.q)
                 union all select key, value from jsonb_each(excluded.q)) x
               group by k) y);
  get diagnostics n = row_count;
  return n;
end $$;

-- Застосувати. p_shops — коди складів «ЧО NN», що є у файлі.
--  • q змінюється лише для магазинів із файлу, у яких у файлі є хоч один товар;
--  • магазин без товарів у файлі не обнуляється (kept);
--  • товару немає у файлі → його кількість для цих магазинів 0 (ключ прибирається; порожній q — рядок видаляється);
--  • товарів у файлі < 50% від наявних — лише з p_confirm;
--  • невідомий склад (немає в stores) — помилка unknown_stores, клієнт питає «Який це магазин?».
create function public.stock_upload_commit(p_id bigint, p_shops text[], p_confirm boolean default false)
returns jsonb
language plpgsql security definer set search_path = '' set statement_timeout = '5min' as $$
declare
  v_active text[];
  v_kept text[];
  v_unknown text[];
  v_file int;
  v_prev int;
  v_changed int := 0;
  v_ins int := 0;
  v_removed int := 0;
  v_cleared int := 0;
  v_sec numeric;
  v_t0 timestamptz := clock_timestamp();
begin
  perform app.require_stock_admin();
  perform 1 from public.stock_uploads where id = p_id and by_uid = app.uploader() and status = 'open' for update;
  if not found then
    raise exception 'upload % is not open', p_id using errcode = 'P0002';
  end if;

  select coalesce(array_agg(c order by c), '{}') into v_unknown
  from unnest(coalesce(p_shops, '{}')) c where not exists (select 1 from public.stores s where s.code = c);
  if cardinality(v_unknown) > 0 then
    raise exception 'unknown_stores' using errcode = 'P0001', detail = array_to_string(v_unknown, ',');
  end if;

  select count(*) into v_file from app.stock_staging where upload_id = p_id;
  select count(*) into v_prev from public.stock;
  if v_prev > 0 and v_file < v_prev / 2.0 and not p_confirm then
    raise exception 'confirm_required' using errcode = 'P0001', detail = format('%s,%s', v_file, v_prev);
  end if;

  -- Магазини, у яких у файлі є товари.
  select coalesce(array_agg(distinct k order by k), '{}') into v_active
  from app.stock_staging s, jsonb_each(s.q) e(k, v)
  where s.upload_id = p_id and k = any (p_shops) and (v)::numeric > 0;
  select coalesce(array_agg(c order by c), '{}') into v_kept
  from unnest(coalesce(p_shops, '{}')) c where not (c = any (v_active));

  -- Нові й змінені товари.
  with fresh as (
    -- Порожні name / code / article (вигрузка 1С без назв) — лишаються як були.
    select s.barcode, nullif(s.name, '') as name, nullif(s.code, '') as code, nullif(s.article, '') as article,
           (select coalesce(jsonb_object_agg(k, v), '{}') from jsonb_each(s.q) e(k, v)
             where k = any (v_active) and (v)::numeric > 0) as q_new
    from app.stock_staging s where s.upload_id = p_id
  ), merged as (
    select f.barcode, coalesce(f.name, st.name, '') as name, coalesce(f.code, st.code, '') as code,
           coalesce(f.article, st.article, '') as article,
           (coalesce(st.q, '{}') - v_active) || f.q_new as q, st.barcode is null as is_new
    from fresh f left join public.stock st on st.barcode = f.barcode
    where st.barcode is null
       or st.name is distinct from coalesce(f.name, st.name) or st.code is distinct from coalesce(f.code, st.code)
       or st.article is distinct from coalesce(f.article, st.article)
       or ((st.q - v_active) || f.q_new) is distinct from st.q
  ), up as (
    insert into public.stock as t (barcode, name, code, article, q, updated_at)
    select barcode, name, code, article, q, now() from merged where q <> '{}'::jsonb or not is_new
    on conflict (barcode) do update
      set name = excluded.name, code = excluded.code, article = excluded.article, q = excluded.q, updated_at = now()
    returning (xmax = 0) as inserted
  )
  select count(*) filter (where not inserted), count(*) filter (where inserted) into v_changed, v_ins from up;

  -- Товарів немає у файлі: прибрати кількість для активних магазинів.
  update public.stock t set q = t.q - v_active, updated_at = now()
  where t.q ?| v_active
    and not exists (select 1 from app.stock_staging s where s.upload_id = p_id and s.barcode = t.barcode);
  get diagnostics v_cleared = row_count;

  delete from public.stock where q = '{}'::jsonb;
  get diagnostics v_removed = row_count;

  delete from app.stock_staging where upload_id = p_id;
  v_sec := round(extract(epoch from clock_timestamp() - v_t0)::numeric, 1);

  update public.stock_uploads
     set status = 'done', at = now(), rows_total = v_file, changed = v_changed + v_ins + v_cleared,
         removed = v_removed, shops = v_active
   where id = p_id;

  insert into public.admin_log (by_uid, action, details)
  values (app.uploader(), 'stockImport', jsonb_build_object(
    'stores', cardinality(v_active), 'items', v_file, 'changed', v_changed + v_ins + v_cleared,
    'removed', v_removed, 'kept', v_kept, 'seconds', v_sec));

  return jsonb_build_object(
    'id', p_id, 'stores', cardinality(v_active), 'items', v_file, 'inserted', v_ins,
    'changed', v_changed + v_ins + v_cleared, 'removed', v_removed, 'kept', v_kept,
    'previous', v_prev, 'seconds', v_sec);
end $$;

create function public.stock_upload_abort(p_id bigint) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform app.require_stock_admin();
  delete from app.stock_staging where upload_id = p_id;
  update public.stock_uploads set status = 'failed' where id = p_id and by_uid = app.uploader() and status = 'open';
end $$;

-- Склади 1С: [{code, name, address, city, region}] — нові або змінені (shop_id не чіпається).
create function public.stores_upsert(p_rows jsonb) returns int
language plpgsql security definer set search_path = '' as $$
declare n int;
begin
  perform app.require_stock_admin();
  insert into public.stores as t (code, name, address, city, region, updated_at)
  select r.code, coalesce(r.name, ''), coalesce(r.address, ''), coalesce(r.city, ''), coalesce(r.region, ''), now()
  from jsonb_to_recordset(p_rows) as r(code text, name text, address text, city text, region text)
  on conflict (code) do update
    set name = excluded.name, address = case when excluded.address = '' then t.address else excluded.address end,
        city = excluded.city, region = case when excluded.region = '' then t.region else excluded.region end,
        updated_at = now()
    where (t.name, t.city) is distinct from (excluded.name, excluded.city)
       or (excluded.address <> '' and t.address is distinct from excluded.address)
       or (excluded.region <> '' and t.region is distinct from excluded.region);
  get diagnostics n = row_count;
  return n;
end $$;

-- «Який це магазин?»: stores.shop_id і shops.code1c разом. p_shop = null — пропустити.
create function public.store_link(p_code text, p_shop text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform app.require_stock_admin();
  update public.stores set shop_id = p_shop, updated_at = now() where code = p_code;
  if not found then raise exception 'unknown store %', p_code using errcode = 'P0002'; end if;
  if p_shop is not null then
    insert into public.shops (id, code1c) values (p_shop, p_code)
    on conflict (id) do update set code1c = excluded.code1c;
  end if;
end $$;

revoke execute on function public.stock_upload_begin(text, text), public.stock_upload_chunk(bigint, jsonb),
  public.stock_upload_commit(bigint, text[], boolean), public.stock_upload_abort(bigint),
  public.stores_upsert(jsonb), public.store_link(text, text) from public, anon;
grant execute on function public.stock_upload_begin(text, text), public.stock_upload_chunk(bigint, jsonb),
  public.stock_upload_commit(bigint, text[], boolean), public.stock_upload_abort(bigint),
  public.stores_upsert(jsonb), public.store_link(text, text) to authenticated, service_role;
