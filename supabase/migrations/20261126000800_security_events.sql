-- Server-generated events. Clients cannot insert, update or delete these records.
create table public.security_events (
  id bigint generated always as identity primary key,
  at timestamptz not null default statement_timestamp(),
  by_uid text,
  action text not null check (action in (
    'account.registered', 'account.profile_changed', 'account.access_changed',
    'printer.added', 'printer.changed', 'printer.removed',
    'device.paired', 'device.unpaired',
    'print.queued', 'print.status_changed', 'stock.imported',
    'session.allowed', 'session.denied', 'session.revoked'
  )),
  target text not null,
  shop_id text,
  ip inet,
  outcome text
);

alter table public.security_events enable row level security;
revoke all on public.security_events from public, anon, authenticated;
grant select on public.security_events to authenticated;
grant insert on public.security_events to service_role;
grant usage on sequence public.security_events_id_seq to service_role;
create policy super_admin_security_events on public.security_events
  for select to authenticated using (app.firebase_ok() and app.is_super());

create function app.record_security_event() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  event_name text;
  record_id text;
  shop text;
  result text;
begin
  case tg_table_name
    when 'users' then
      record_id := new.uid;
      shop := new.shop_id;
      if tg_op = 'INSERT' then
        event_name := 'account.registered';
      elsif row(new.approved, new.admin, new.super_admin, new.stock_admin, new.shop_id)
          is distinct from row(old.approved, old.admin, old.super_admin, old.stock_admin, old.shop_id) then
        event_name := 'account.access_changed';
      elsif row(new.name, new.phone, new.region, new.shop)
          is distinct from row(old.name, old.phone, old.region, old.shop) then
        event_name := 'account.profile_changed';
      end if;
    when 'printers' then
      if tg_op = 'DELETE' then
        event_name := 'printer.removed'; record_id := old.id; shop := old.shop_id;
      else
        record_id := new.id; shop := new.shop_id;
        if tg_op = 'INSERT' then
          event_name := 'printer.added';
        elsif to_jsonb(new) - 'last_seen' - 'last_ok_by'
            is distinct from to_jsonb(old) - 'last_seen' - 'last_ok_by' then
          event_name := 'printer.changed';
        end if;
      end if;
    when 'devices' then
      if tg_op = 'DELETE' then
        event_name := 'device.unpaired'; record_id := old.uid; shop := old.shop_id;
      elsif tg_op = 'INSERT' then
        event_name := 'device.paired'; record_id := new.uid; shop := new.shop_id;
      elsif row(new.shop_id, new.approved_by, new.paired_at)
          is distinct from row(old.shop_id, old.approved_by, old.paired_at) then
        event_name := 'device.paired'; record_id := new.uid; shop := new.shop_id;
      end if;
    when 'print_jobs' then
      record_id := new.id::text; shop := new.shop_id; result := new.status;
      if tg_op = 'INSERT' then
        event_name := 'print.queued';
      elsif new.status is distinct from old.status then
        event_name := 'print.status_changed';
      end if;
    when 'stock_uploads' then
      record_id := new.id::text;
      if new.status = 'done' and (tg_op = 'INSERT' or old.status is distinct from 'done') then
        event_name := 'stock.imported';
      end if;
  end case;

  if event_name is not null then
    insert into public.security_events (by_uid, action, target, shop_id, outcome)
    values (app.uid(), event_name, record_id, nullif(shop, ''), result);
  end if;
  return null;
end
$$;

revoke execute on function app.record_security_event() from public, anon, authenticated;

create trigger security_events_users after insert or update on public.users
  for each row execute function app.record_security_event();
create trigger security_events_printers after insert or update or delete on public.printers
  for each row execute function app.record_security_event();
create trigger security_events_devices after insert or update or delete on public.devices
  for each row execute function app.record_security_event();
create trigger security_events_print_jobs after insert or update of status on public.print_jobs
  for each row execute function app.record_security_event();
create trigger security_events_stock_uploads after insert or update of status on public.stock_uploads
  for each row execute function app.record_security_event();

-- The trusted gateway can write observed IPs for authentication events.
-- Database triggers deliberately leave IP empty because client headers are not trusted.
