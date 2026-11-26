-- Час — серверний (годинник ПК чи телефона буває неточним): пульс агента, початок і кінець друку.

create function app.agents_touch() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.last_seen := now();
  return new;
end $$;
create trigger agents_touch before insert or update on public.agents for each row execute function app.agents_touch();

create function app.print_jobs_times() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.status = 'printing' and old.status is distinct from 'printing' then new.started_at := now(); end if;
  if new.status in ('done', 'error') and old.status is distinct from new.status then new.done_at := now(); end if;
  if new.status = 'queued' then new.started_at := null; new.done_at := null; end if;
  return new;
end $$;
create trigger print_jobs_times before update on public.print_jobs for each row execute function app.print_jobs_times();
