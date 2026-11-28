-- Примусова зміна тимчасового пароля: після скидання адміном співробітник
-- при вході бачить «Придумайте новий пароль» і не працює далі, доки не змінить його.
-- Позначку ставить лише password_reset_finish; знімає лише сам співробітник (password_changed).
-- Клієнтам колонка лише для читання: її немає в grant update на users.

alter table public.users add column must_change_password boolean not null default false;

create or replace function public.password_reset_finish(p_id uuid) returns void
language plpgsql volatile security definer set search_path = '' as $$
declare v_target text := public.password_reset_target(p_id);
begin
  update public.password_requests set status = 'done', done_by = app.uid(), done_at = now() where id = p_id;
  update public.users set must_change_password = true where uid = v_target;
  insert into public.admin_log (by_uid, action, target, shop_id)
    select app.uid(), 'passwordReset', v_target, r.shop_id from public.password_requests r where r.id = p_id;
end
$$;
revoke all on function public.password_reset_finish(uuid) from public, anon;
grant execute on function public.password_reset_finish(uuid) to authenticated;

-- Співробітник змінив тимчасовий пароль у застосунку — зняти позначку (лише собі).
create function public.password_changed() returns void
language plpgsql volatile security definer set search_path = '' as $$
begin
  if not app.firebase_ok() then raise exception 'not allowed' using errcode = '42501'; end if;
  update public.users set must_change_password = false where uid = app.uid();
end
$$;
revoke all on function public.password_changed() from public, anon;
grant execute on function public.password_changed() to authenticated;
