-- Запити залишків для застосунку (security invoker: діють ті самі RLS, що й на stock).

-- «Що в магазині ЧО NN»: товари з кількістю > 0 саме там, найбільші залишки першими.
create function public.stock_in_store(p_code text, p_limit int default 100)
returns setof public.stock
language sql stable set search_path = '' as $$
  select s.* from public.stock s
  where s.q ? p_code and (s.q ->> p_code)::numeric > 0
  order by (s.q ->> p_code)::numeric desc, s.barcode
  limit least(greatest(p_limit, 1), 1000)
$$;

-- «Змінені з минулого завантаження», що є в магазині p_code: товари, які змінило останнє успішне
-- завантаження (updated_at = час його commit). Першого завантаження ще не порівнювали — порожньо.
create function public.stock_changed_last_import(p_code text, p_limit int default 500)
returns setof public.stock
language sql stable set search_path = '' as $$
  with last as (
    select at from public.stock_uploads where status = 'done' order by at desc, id desc limit 2
  )
  select s.* from public.stock s
  where (select count(*) from last) = 2
    and s.updated_at >= (select max(at) from last)
    and s.q ? p_code and (s.q ->> p_code)::numeric > 0
  order by s.name
  limit least(greatest(p_limit, 1), 2000)
$$;

revoke execute on function public.stock_in_store(text, int), public.stock_changed_last_import(text, int) from public, anon;
grant execute on function public.stock_in_store(text, int), public.stock_changed_last_import(text, int) to authenticated;
