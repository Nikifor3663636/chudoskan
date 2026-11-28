-- «Мій магазин» → «Журнал дій»: адмін магазину читає записи свого магазину
-- (адмін мережі — увесь журнал), а пише лише про свій магазин.
drop policy log_select on public.admin_log;
create policy log_select on public.admin_log for select to authenticated
  using (app.is_super() or (shop_id is not null and shop_id <> '' and app.is_shop_admin(shop_id)));

drop policy log_insert on public.admin_log;
create policy log_insert on public.admin_log for insert to authenticated
  with check (by_uid = app.uid()
              and (app.is_super()
                   or (app.is_stock_admin() and action = 'stockImport')
                   or (app.is_admin() and (shop_id is null or shop_id = '' or app.is_shop_admin(shop_id)))));

create index if not exists admin_log_shop_at on public.admin_log (shop_id, at desc);
