-- Посада в магазині: керуючий, старший продавець, продавець ('' — не вказано).
-- Посада — лише підпис для колективу; права доступу дають approved / admin, як і раніше.
-- Ставить адмін цього магазину або адмін мережі (собі теж); сам співробітник посаду не змінює.

alter table public.users add column job_title text not null default ''
  check (job_title in ('', 'manager', 'senior', 'seller'));

grant update (job_title) on public.users to authenticated;

create function app.users_job_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user in ('authenticated', 'anon')
     and new.job_title is distinct from old.job_title
     and not app.can_manage(old.shop_id) then
    raise exception 'job title is set by the shop admin' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger users_job_guard before update on public.users for each row execute function app.users_job_guard();

-- Колектив поточного магазину (для адміна мережі — магазин «Працювати як у ЧО NN»):
-- лише підтверджені, без адмінів мережі; без телефонів — колегам видно ім'я й посаду.
create function public.shop_team()
returns table (uid text, name text, job_title text, admin boolean)
language plpgsql stable security definer set search_path = '' as $$
declare v_shop text;
begin
  if not app.firebase_ok() or not app.is_approved() then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  v_shop := app.acting_shop();
  if not app.can_see(v_shop) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  return query
    select u.uid, u.name, u.job_title, u.admin from public.users u
    where u.shop_id = v_shop and u.approved and not u.super_admin
    order by u.name;
end
$$;
revoke all on function public.shop_team() from public, anon;
grant execute on function public.shop_team() to authenticated;
