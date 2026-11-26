-- Серверний час — лише для запитів клієнтів (authenticated). Перенесення з Firestore (service_role)
-- зберігає справжні lastSeen / startedAt / doneAt, інакше старі ПК на 3 хв виглядали б «на зв'язку».

create or replace function app.agents_touch() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user = 'authenticated' then new.last_seen := now(); end if;
  return new;
end $$;

create or replace function app.print_jobs_times() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user <> 'authenticated' then return new; end if;
  if new.status = 'printing' and old.status is distinct from 'printing' then new.started_at := now(); end if;
  if new.status in ('done', 'error') and old.status is distinct from new.status then new.done_at := now(); end if;
  if new.status = 'queued' then new.started_at := null; new.done_at := null; end if;
  return new;
end $$;
