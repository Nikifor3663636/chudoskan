-- «Профіль → Принтери» для всіх співробітників: підключати, налаштовувати (назва, щільність,
-- швидкість) і прибирати принтери свого магазину може кожен підтверджений співробітник.
-- Чужі магазини — як і раніше, ні (can_see = працює в цьому магазині або адмін мережі).

drop policy printers_delete on public.printers;
create policy printers_delete on public.printers for delete to authenticated using (app.can_see(shop_id));

create or replace function app.printers_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user not in ('authenticated', 'anon') then return new; end if;
  if tg_op = 'UPDATE' and (new.shop_id, new.id) is distinct from (old.shop_id, old.id) then
    raise exception 'printer key is fixed' using errcode = '42501';
  end if;
  new.last_seen := now();
  return new;
end $$;
