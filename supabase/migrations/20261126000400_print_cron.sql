-- Друк: Realtime для реєстру принтерів і підмереж; обслуговування черги print_jobs (pg_cron).

alter publication supabase_realtime add table public.printers, public.shops;

-- Зависле printing > 2 хв повертається в чергу один раз; удруге — error.
-- done / error старші 7 днів — видаляються.
create function app.print_jobs_maintain() returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.print_jobs
     set status = case when attempts < 2 then 'queued' else 'error' end,
         error = case when attempts < 2 then error else 'Комп''ютер не завершив друк (зависло понад 2 хв)' end,
         started_at = case when attempts < 2 then null else started_at end,
         done_at = case when attempts < 2 then null else now() end
   where status = 'printing' and started_at < now() - interval '2 minutes';
  delete from public.print_jobs
   where status in ('done', 'error') and coalesce(done_at, created_at) < now() - interval '7 days';
  -- Прострочені коди підключення ПК.
  delete from public.device_pairing where expires_at < now() - interval '1 hour';
end $$;
revoke all on function app.print_jobs_maintain() from public, anon, authenticated;

create extension if not exists pg_cron;
select cron.schedule('print-jobs-maintain', '* * * * *', 'select app.print_jobs_maintain()');
