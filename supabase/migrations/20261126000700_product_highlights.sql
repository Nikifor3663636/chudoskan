create table public.product_highlights (
  kind text not null check (kind in ('new', 'promo')),
  barcode text not null default '' check (barcode = '' or barcode ~ '^[0-9]{8,14}$'),
  article text not null default '' check (length(article) <= 100),
  name text not null check (length(name) between 1 and 300),
  starts_on date,
  ends_on date,
  promo_price numeric(12,2) check (promo_price is null or (kind = 'promo' and promo_price >= 0)),
  check (barcode <> '' or article <> ''),
  check (starts_on is null or ends_on is null or starts_on <= ends_on),
  primary key (kind, barcode, article)
);

create index product_highlights_barcode on public.product_highlights (barcode);
create index product_highlights_article on public.product_highlights (lower(article));

alter table public.product_highlights enable row level security;
revoke all on public.product_highlights from anon, authenticated;
grant select on public.product_highlights to authenticated;
create policy highlights_active_read on public.product_highlights for select to authenticated
using (
  app.firebase_ok() and app.is_approved()
  and (starts_on is null or starts_on <= (now() at time zone 'Europe/Kyiv')::date)
  and (ends_on is null or ends_on >= (now() at time zone 'Europe/Kyiv')::date)
);

create function public.publish_product_highlights(p_kind text, p_rows jsonb) returns integer
language plpgsql security definer set search_path = '' as $$
declare
  published integer;
begin
  if not (app.firebase_ok() and app.is_stock_admin()) then
    raise exception 'Потрібні права адміністратора залишків' using errcode = '42501';
  end if;
  if p_kind is null or p_kind not in ('new', 'promo')
     or p_rows is null or jsonb_typeof(p_rows) <> 'array' then
    raise exception 'Некоректний формат звіту' using errcode = '22023';
  end if;
  if jsonb_array_length(p_rows) not between 1 and 10000 then
    raise exception 'Звіт має містити від 1 до 10 000 товарів' using errcode = '22023';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(20261126, case when p_kind = 'new' then 1 else 2 end);
  delete from public.product_highlights where kind = p_kind;
  insert into public.product_highlights (kind, barcode, article, name, starts_on, ends_on, promo_price)
  select p_kind, btrim(coalesce(r.barcode, '')), btrim(coalesce(r.article, '')), btrim(r.name),
         r.starts_on, r.ends_on, r.promo_price
  from jsonb_to_recordset(p_rows) as r(
    barcode text, article text, name text, starts_on date, ends_on date, promo_price numeric
  );
  get diagnostics published = row_count;
  return published;
end;
$$;

revoke all on function public.publish_product_highlights(text, jsonb) from public, anon;
grant execute on function public.publish_product_highlights(text, jsonb) to authenticated;

create function public.list_product_highlights(
  p_kind text, p_query text default '', p_offset integer default 0
) returns setof public.product_highlights
language sql stable set search_path = '' as $$
  select h.* from public.product_highlights h
  where h.kind = p_kind
    and (
      coalesce(p_query, '') = '' or strpos(lower(h.name), lower(p_query)) > 0
      or h.barcode = p_query or lower(h.article) = lower(p_query)
    )
  order by lower(h.name), h.barcode, h.article
  limit 50 offset greatest(p_offset, 0)
$$;

revoke all on function public.list_product_highlights(text, text, integer) from public, anon;
grant execute on function public.list_product_highlights(text, text, integer) to authenticated;

create function public.product_highlights_for(p_barcode text default '', p_article text default '')
returns setof public.product_highlights
language sql stable set search_path = '' as $$
  select distinct on (h.kind) h.* from public.product_highlights h
  where (p_barcode <> '' and h.barcode = p_barcode)
     or (p_article <> '' and lower(h.article) = lower(p_article))
  order by h.kind, (h.barcode = p_barcode and p_barcode <> '') desc nulls last,
           h.starts_on desc nulls last, h.barcode, h.article
$$;

revoke all on function public.product_highlights_for(text, text) from public, anon;
grant execute on function public.product_highlights_for(text, text) to authenticated;
