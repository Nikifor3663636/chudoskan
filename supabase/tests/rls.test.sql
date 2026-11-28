-- Права в Supabase: ті самі випадки, що rules-tests/rules.test.js (Firestore), плюс чужий aud.
-- Запуск: supabase test db (локально) або supabase test db --remote.
begin;
create extension if not exists pgtap with schema extensions;
set search_path = public, extensions;

select * from no_plan();

-- ---------- Помічники ----------
create schema tests;
grant usage on schema tests to authenticated, anon;

create function tests.login(u text, proj text default 'chudoostriv', extra jsonb default '{}')
returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims',
    (jsonb_build_object('sub', u, 'iss', 'https://securetoken.google.com/' || proj, 'aud', proj,
                        'role', 'authenticated') || extra)::text, true);
  perform set_config('role', 'authenticated', true);
end $$;

create function tests.anon() returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', '{"role":"anon"}', true);
  perform set_config('role', 'anon', true);
end $$;

-- Дозволено: без помилки й зачепило хоч один рядок. Наслідки відкочуються.
create function tests.ok(q text) returns boolean language plpgsql as $$
begin return tests.rows(q) > 0; end $$;

-- Кількість рядків (з відкотом).
create function tests.rows(q text) returns int language plpgsql as $$
declare c int;
begin
  begin
    execute q;
    get diagnostics c = row_count;
    raise exception using errcode = 'ZZ002', message = c::text;
  exception
    when sqlstate 'ZZ002' then return sqlerrm::int;
    when others then raise notice 'denied: % | %', sqlerrm, left(q, 120); return -1;
  end;
end $$;

-- Заборонено: помилка або 0 рядків (RLS приховав).
create function tests.no(q text) returns boolean language plpgsql as $$
begin return tests.rows(q) <= 0; end $$;

-- Крок сценарію — по-справжньому.
create function tests.run(q text) returns int language plpgsql as $$
declare c int;
begin execute q; get diagnostics c = row_count; return c; end $$;

grant execute on all functions in schema tests to authenticated, anon;

-- ---------- Дані (як beforeEach у rules.test.js) ----------
insert into users (uid, name, approved, admin, super_admin, stock_admin, shop_id) values
  ('emp22', 'Оля', true, false, false, false, '22'),
  ('emp62', 'Іра', true, false, false, false, '62'),
  ('adm22', 'Адмін 22', true, true, false, false, '22'),
  ('adm62', 'Адмін 62', true, true, false, false, '62'),
  ('sup', 'Мережа', true, false, true, false, '1'),
  ('stocker', 'Залишки', true, false, false, true, '5'),
  ('new22', 'Новенька', false, false, false, false, '22'),
  ('new62', 'Новенький', false, false, false, false, '62'),
  ('supNotApproved', 'Х', false, false, true, false, '1');
insert into shops (id, region, address) values ('22', 'Київ', 'вул. Тестова, 1'), ('62', 'Одеса', 'вул. Інша, 2');
insert into devices (uid, shop_id, name, code, approved_by) values
  ('pc22', '22', 'КАСА-22', '111111', 'adm22'), ('pc62', '62', 'КАСА-62', '222222', 'adm62');
insert into device_pairing (code, uid, pc_name, expires_at) values
  ('123456', 'pcNew', 'НОВИЙ-ПК', now() + interval '10 min'),
  ('654321', 'pcOld', 'СТАРИЙ-ПК', now() - interval '1 min'),
  ('777777', 'pc62new', 'ПК-62', now() + interval '10 min');
insert into printers (shop_id, id, model, kind, ip, port, transport) values
  ('62', 'g', 'Godex G500', 'label_40x24', '192.0.2.10', 9100, 'tcp'),
  ('22', 'g', 'Godex G500', 'label_40x24', '192.0.2.10', 9100, 'tcp');
insert into agents (uid, shop_id, pc_name, online) values ('pc22', '22', 'КАСА-22', true);
insert into print_jobs (id, agent_id, printer, shop_id, by_uid, status, lang, data, size) values
  ('00000000-0000-0000-0000-000000000022', 'pc22', 'Godex G500', '22', 'emp22', 'queued', 'EZPL', '\x01', 1);
insert into stock (barcode, q, name, code, article) values ('4823077601234', '{"22": 3}', 'LEGO', 'BOC1', '10954');
insert into stores (code, name, city, shop_id) values ('22', 'ЧО 22', 'Київ', '22'), ('62', 'ЧО 62', 'Одеса', '62');
insert into stock_uploads (by_uid, by_name, status, rows_total, shops) values ('stocker', 'Залишки', 'done', 1, '{22}');
insert into admin_log (by_uid, action, target, shop_id) values ('adm22', 'approve', 'new22', '22');

-- Зручні шаблони.
create function tests.job(over text default '') returns text language sql as $$
  select format($q$insert into print_jobs (agent_id, printer, shop_id, by_uid, lang, data, size) values (%s)$q$,
    coalesce(nullif(over, ''), $v$'pc22', 'Godex G500', '22', 'emp22', 'EZPL', '\x010203', 3$v$))
$$;
grant execute on function tests.job(text) to authenticated;

-- ===================== Чужий токен =====================
select tests.login('emp22', 'other-project');
select ok(tests.no($$select * from stock$$), 'токен з чужим aud/iss: залишки не видно');
select ok(tests.no($$select * from users where uid = 'emp22'$$), 'токен з чужим aud: своя анкета не видна');
select ok(tests.no($$insert into users (uid, name) values ('emp22x', 'x')$$), 'токен з чужим aud: реєстрація не проходить');
select tests.login('emp22', 'chudoostriv', '{"aud": "other-project"}');
select ok(tests.no($$select * from stock$$), 'наш iss, але чужий aud — не проходить');
select tests.anon();
select ok(tests.no($$select * from stock$$), 'анонім (без токена) не читає залишки');
select ok(tests.no($$insert into device_pairing (code, uid, pc_name, expires_at) values ('345672', 'pcX', 'ПК', now() + interval '15 min')$$), 'анонім не створює код ПК');

-- ===================== Чужий магазин =====================
select tests.login('emp22');
select ok(tests.ok($$select * from shops where id = '22'$$), 'emp22 бачить ЧО 22');
select ok(tests.no($$select * from shops where id = '62'$$), 'emp22 не бачить ЧО 62');
select ok(tests.no($$select * from printers where shop_id = '62'$$), 'emp22 не бачить принтери ЧО 62');
select ok(tests.no($$insert into printers (shop_id, id, model, kind, ip, port, transport) values ('62', 'x', 'Godex', 'label_40x24', '192.0.2.1', 9100, 'tcp')$$), 'emp22 не пише принтер ЧО 62');
select ok(tests.no($$select * from agents where shop_id = '62'$$), 'emp22 не бачить агентів ЧО 62');
select ok(tests.ok($$select * from agents where shop_id = '22'$$), 'emp22 бачить агентів ЧО 22');

select tests.login('adm22');
select ok(tests.no($$delete from printers where shop_id = '62' and id = 'g'$$), 'adm22 не видаляє принтер ЧО 62');
select ok(tests.no($$update shops set code1c = '62' where id = '62'$$), 'adm22 не змінює code1c ЧО 62');

-- ===================== users =====================
select tests.login('adm22');
select ok(tests.no($$select * from users where uid = 'emp62'$$), 'adm22 не бачить emp62');
select ok(tests.no($$select * from users where shop_id = '62'$$), 'adm22 не бачить список ЧО 62');
select ok(tests.no($$update users set approved = true where uid = 'new62'$$), 'adm22 не підтверджує new62');
select ok(tests.no($$update users set admin = true where uid = 'emp62'$$), 'adm22 не робить адміном emp62');
select ok(tests.ok($$select * from users where uid = 'emp22'$$), 'adm22 бачить emp22');
select ok(tests.ok($$select * from users where shop_id = '22'$$), 'adm22 бачить список ЧО 22');

select ok(tests.ok($$update users set approved = true, approved_by = 'adm22', changed_at = now() where uid = 'new22'$$), 'adm22 підтверджує new22');
select ok(tests.no($$update users set shop_id = '62' where uid = 'emp22'$$), 'adm22 не переводить в інший магазин');
select ok(tests.no($$update users set admin = true, shop_id = '62' where uid = 'emp22'$$), 'adm22 не робить адміном в іншому магазині');
select ok(tests.no($$update users set approved_by = 'someoneElse' where uid = 'emp22'$$), 'approved_by — лише свій uid');
select ok(tests.no($$update users set name = 'Інше ім''я' where uid = 'emp22'$$), 'адмін не змінює чуже ім''я');
select ok(tests.ok($$update users set admin = true where uid = 'emp22'$$), 'adm22 робить emp22 адміном');

select tests.login('emp22');
select ok(tests.ok($$select * from users where uid = 'emp22'$$), 'співробітник бачить себе');
select is(tests.rows($$select * from users where shop_id = '22'$$), 1, 'співробітник у списку ЧО 22 бачить лише себе');
select is(tests.rows($$select * from users$$), 1, 'співробітник не читає всіх users');
select ok(tests.no($$select * from users where uid = 'new22'$$), 'співробітник не бачить чужу анкету');
select ok(tests.no($$update users set approved = true where uid = 'new22'$$), 'співробітник не підтверджує');

-- Ніхто не ставить super_admin / stock_admin.
select tests.login('emp22');
select ok(tests.no($$update users set super_admin = true where uid = 'emp22'$$), 'emp22 → emp22.super_admin');
select ok(tests.no($$update users set stock_admin = true where uid = 'emp22'$$), 'emp22 → emp22.stock_admin');
select tests.login('new22');
select ok(tests.no($$update users set super_admin = true where uid = 'new22'$$), 'new22 → new22.super_admin');
select ok(tests.no($$update users set stock_admin = true where uid = 'new22'$$), 'new22 → new22.stock_admin');
select tests.login('adm22');
select ok(tests.no($$update users set super_admin = true where uid = 'emp22'$$), 'adm22 → emp22.super_admin');
select ok(tests.no($$update users set stock_admin = true where uid = 'emp22'$$), 'adm22 → emp22.stock_admin');
select ok(tests.no($$update users set super_admin = true where uid = 'adm22'$$), 'adm22 → adm22.super_admin');
select ok(tests.no($$update users set stock_admin = true where uid = 'adm22'$$), 'adm22 → adm22.stock_admin');
select tests.login('sup');
select ok(tests.no($$update users set super_admin = true where uid = 'emp22'$$), 'sup → emp22.super_admin');
select ok(tests.no($$update users set stock_admin = true where uid = 'emp22'$$), 'sup → emp22.stock_admin');
select ok(tests.no($$update users set super_admin = false where uid = 'sup'$$), 'sup → sup.super_admin');
select ok(tests.no($$update users set stock_admin = true where uid = 'sup'$$), 'sup → sup.stock_admin');
select tests.login('stocker');
select ok(tests.no($$update users set super_admin = true where uid = 'stocker'$$), 'stocker → stocker.super_admin');
select ok(tests.no($$update users set stock_admin = false where uid = 'stocker'$$), 'stocker → stocker.stock_admin');
select tests.login('fresh');
select ok(tests.no($$insert into users (uid, name, shop_id, super_admin) values ('fresh', 'Я', '22', true)$$), 'реєстрація з super_admin — ні');
select ok(tests.no($$insert into users (uid, name, shop_id, approved) values ('fresh', 'Я', '22', true)$$), 'реєстрація одразу підтвердженою — ні');
select ok(tests.no($$insert into users (uid, name, shop_id) values ('other', 'Я', '22')$$), 'анкета за чужий uid — ні');

-- Сам: реєстрація, ім'я; ролі не змінює.
select ok(tests.ok($$insert into users (uid, name, shop_id, region, shop) values ('fresh', 'Я', '22', 'Київ', 'ЧО 22')$$), 'реєстрація');
select ok(tests.no($$insert into users (uid, name, shop_id, phone) values ('fresh', 'Я', '22', '+380961234567')$$), 'чужий телефон в анкеті — ні');
select tests.login('fresh', 'chudoostriv', '{"email": "380961234567@tel.chudoostriv.app"}');
select ok(tests.ok($$insert into users (uid, name, shop_id, phone) values ('fresh', 'Я', '22', '+380961234567')$$), 'телефон = логін — так');
select tests.login('emp22');
select ok(tests.ok($$update users set name = 'Ольга' where uid = 'emp22'$$), 'emp22 змінює ім''я');
select ok(tests.no($$update users set shop_id = '62' where uid = 'emp22'$$), 'підтверджений не змінює магазин');
select ok(tests.no($$update users set admin = true where uid = 'emp22'$$), 'сам не стає адміном');
select tests.login('new22');
select ok(tests.no($$update users set approved = true where uid = 'new22'$$), 'сам не підтверджується');
select ok(tests.ok($$update users set shop_id = '62' where uid = 'new22'$$), 'до підтвердження змінює магазин');
select tests.login('sup');
select ok(tests.ok($$update users set name = 'Мережа Київ' where uid = 'sup'$$), 'sup змінює своє ім''я');

-- superAdmin.
select ok(tests.rows($$select * from users$$) >= 9, 'sup бачить усіх');
select ok(tests.ok($$update users set approved = true, approved_by = 'sup', changed_at = now() where uid = 'new62'$$), 'sup підтверджує будь-кого');
select ok(tests.ok($$update users set shop_id = '62', shop = 'ЧО 62', admin = true where uid = 'emp22'$$), 'sup переводить між магазинами');
select tests.login('supNotApproved');
select ok(tests.no($$select * from users where uid = 'emp22'$$), 'непідтверджений super_admin не бачить чужих');
select ok(tests.no($$update users set approved = true where uid = 'new62'$$), 'непідтверджений super_admin не підтверджує');

-- ===================== Підключення ПК =====================
select tests.login('pcX');
select ok(tests.ok($$insert into device_pairing (code, uid, pc_name, expires_at) values ('345678', 'pcX', 'ПК', now() + interval '15 min')$$), 'ПК створює код');
select ok(tests.no($$insert into device_pairing (code, uid, pc_name, expires_at) values ('012345', 'pcX', 'ПК', now() + interval '15 min')$$), 'код з 0 — ні');
select ok(tests.no($$insert into device_pairing (code, uid, pc_name, expires_at) values ('12345', 'pcX', 'ПК', now() + interval '15 min')$$), 'код з 5 цифр — ні');
select ok(tests.no($$insert into device_pairing (code, uid, pc_name, expires_at) values ('345679', 'pcX', 'ПК', now() + interval '30 min')$$), 'код на 30 хв — ні');
select ok(tests.no($$insert into device_pairing (code, uid, pc_name, expires_at) values ('345670', 'інший', 'ПК', now() + interval '15 min')$$), 'код за чужий uid — ні');
select ok(tests.no($$insert into device_pairing (code, uid, pc_name, expires_at) values ('123456', 'pcX', 'ПК', now() + interval '15 min')$$), 'чужий код не перезаписати');
select ok(tests.no($$update device_pairing set uid = 'pcX' where code = '123456'$$), 'чужий код не змінити');

select tests.login('adm22');
select ok(tests.ok($$select * from device_pairing_get('123456')$$), 'код читає адмін');
select tests.login('sup');
select ok(tests.ok($$select * from device_pairing_get('123456')$$), 'код читає sup');
select tests.login('emp22');
select ok(tests.no($$select * from device_pairing_get('123456')$$), 'співробітник код не читає');
select tests.login('adm22');
select ok(tests.no($$select * from device_pairing$$), 'список кодів адміну закритий');
select ok(tests.no($$update device_pairing set pc_name = 'x' where code = '123456'$$), 'код не змінюється');
select tests.login('emp22');
select ok(tests.no($$delete from device_pairing where code = '123456'$$), 'співробітник не видаляє код');
select tests.login('pcNew');
select ok(tests.ok($$delete from device_pairing where code = '123456'$$), 'ПК видаляє свій код');

select tests.login('adm22');
select ok(tests.ok($$select device_pair('123456', '22')$$), 'adm22 підключає ПК свого магазину');
select ok(tests.no($$insert into devices (uid, shop_id, name, approved_by) values ('pcHack', '22', 'x', 'adm22')$$), 'devices напряму — ні');
select ok(tests.no($$select device_pair('999999', '22')$$), 'без коду — ні');
select ok(tests.no($$select device_pair('654321', '22')$$), 'минулий код не підходить');
select ok(tests.no($$select device_pair('777777', '62')$$), 'adm22 не підключає ПК до ЧО 62');
select tests.login('emp22');
select ok(tests.no($$select device_pair('123456', '22')$$), 'співробітник не підключає ПК');
select tests.login('pcNew');
select ok(tests.no($$select device_pair('123456', '22')$$), 'ПК не підключає сам себе');
select tests.login('sup');
select ok(tests.ok($$select device_pair('777777', '62')$$), 'sup підключає ПК будь-якого магазину');

-- Після підключення (по-справжньому).
select tests.login('adm22');
select tests.run($$select device_pair('123456', '22')$$);
select tests.login('pcNew');
select ok(tests.ok($$select * from devices where uid = 'pcNew'$$), 'ПК бачить свій devices');
select tests.login('emp22');
select ok(tests.ok($$select * from devices where uid = 'pcNew'$$), 'магазин бачить свій ПК');
select tests.login('emp62');
select ok(tests.no($$select * from devices where uid = 'pcNew'$$), 'інший магазин не бачить ПК');
select tests.login('adm22');
select ok(tests.no($$update devices set name = 'інший' where uid = 'pcNew'$$), 'devices не змінюється');

-- Пристрій без devices нічого не пише.
select tests.login('pcOld');
select ok(tests.no($$insert into agents (uid, shop_id, pc_name, online) values ('pcOld', '22', 'ПК', true)$$), 'ПК без devices не пише agents');
select ok(tests.no($$update print_jobs set status = 'printing' where id = '00000000-0000-0000-0000-000000000022'$$), 'ПК без devices не бере завдання');

-- ===================== agents =====================
select tests.login('pc22');
select ok(tests.ok($$insert into agents (uid, shop_id, pc_name, online, version, queue, last_error, printers) values ('pc22', '22', 'КАСА-22', true, '2.0', 0, '', '[{"name":"Godex G500","isDefault":true,"kind":"label_40x24"}]') on conflict (uid) do update set online = excluded.online, printers = excluded.printers$$), 'ПК пише свій пульс');
select ok(tests.no($$update agents set shop_id = '62' where uid = 'pc22'$$), 'ПК не переносить себе в ЧО 62');
select ok(tests.no($$insert into agents (uid, shop_id, pc_name) values ('pc62', '62', 'x')$$), 'ПК не пише чужий пульс');
select tests.login('emp22');
select ok(tests.no($$insert into agents (uid, shop_id, pc_name) values ('emp22', '22', 'x')$$), 'співробітник не пише agents');
select ok(tests.ok($$select * from agents where uid = 'pc22'$$), 'agents читає свій магазин');
select tests.login('sup');
select ok(tests.ok($$select * from agents where uid = 'pc22'$$), 'agents читає sup');
select tests.login('pc22');
select ok(tests.ok($$select * from agents where uid = 'pc22'$$), 'agents читає сам ПК');
select tests.login('emp62');
select ok(tests.no($$select * from agents where uid = 'pc22'$$), 'інший магазин agents не читає');
select tests.login('new22');
select ok(tests.no($$select * from agents where uid = 'pc22'$$), 'непідтверджений agents не читає');

-- ===================== print_jobs =====================
select tests.login('emp22');
select ok(tests.ok(tests.job()), 'співробітник створює завдання для ПК свого магазину');
select ok(tests.no(tests.job($v$'pcGhost', 'Godex', '22', 'emp22', 'EZPL', '\x01', 1$v$)), 'не підключений ПК — ні');
select ok(tests.no(tests.job($v$'pc62', 'Godex', '22', 'emp22', 'EZPL', '\x01', 1$v$)), 'ПК іншого магазину — ні');
select ok(tests.no(tests.job($v$'pc62', 'Godex', '62', 'emp22', 'EZPL', '\x01', 1$v$)), 'чужий магазин — ні');
select ok(tests.no(tests.job($v$'pc22', 'Godex', '22', 'emp62', 'EZPL', '\x01', 1$v$)), 'від чужого імені — ні');
select ok(tests.no($$insert into print_jobs (agent_id, printer, shop_id, status, data) values ('pc22', 'Godex', '22', 'done', '\x01')$$), 'одразу done — ні');
select tests.login('new22');
select ok(tests.no(tests.job($v$'pc22', 'Godex', '22', 'new22', 'EZPL', '\x01', 1$v$)), 'непідтверджений не друкує');
select tests.login('sup');
select ok(tests.ok(tests.job($v$'pc62', 'Godex', '62', 'sup', 'EZPL', '\x01', 1$v$)), 'sup друкує в будь-якому магазині');

select tests.login('emp22');
select ok(tests.ok($$select * from print_jobs where id = '00000000-0000-0000-0000-000000000022'$$), 'завдання читає автор');
select tests.login('pc22');
select ok(tests.ok($$select * from print_jobs where agent_id = 'pc22'$$), 'завдання читає ПК-адресат');
select tests.login('adm22');
select ok(tests.no($$select * from print_jobs where id = '00000000-0000-0000-0000-000000000022'$$), 'адмін чуже завдання не читає');
select tests.login('pc62');
select ok(tests.no($$select * from print_jobs where id = '00000000-0000-0000-0000-000000000022'$$), 'інший ПК не читає');

select tests.login('pc22');
select ok(tests.ok($$update print_jobs set status = 'printing', started_at = now(), attempts = 1 where id = '00000000-0000-0000-0000-000000000022' and status = 'queued' returning id$$), 'ПК бере завдання (queued → printing)');
select ok(tests.no($$update print_jobs set data = '\x09' where id = '00000000-0000-0000-0000-000000000022'$$), 'дані завдання не змінюються');
select ok(tests.no($$update print_jobs set status = 'зламано' where id = '00000000-0000-0000-0000-000000000022'$$), 'невідомий статус — ні');
select tests.login('emp22');
select ok(tests.no($$update print_jobs set status = 'done' where id = '00000000-0000-0000-0000-000000000022'$$), 'автор статус не змінює');
select tests.login('pc62');
select ok(tests.no($$update print_jobs set status = 'done' where id = '00000000-0000-0000-0000-000000000022'$$), 'інший ПК статус не змінює');
select tests.login('pc22');
select ok(tests.no($$delete from print_jobs where id = '00000000-0000-0000-0000-000000000022'$$), 'незавершене не видаляється');
select tests.run($$update print_jobs set status = 'done', done_at = now() where id = '00000000-0000-0000-0000-000000000022'$$);
select tests.login('emp22');
select ok(tests.no($$delete from print_jobs where id = '00000000-0000-0000-0000-000000000022'$$), 'автор не видаляє');
select tests.login('pc22');
select ok(tests.ok($$delete from print_jobs where id = '00000000-0000-0000-0000-000000000022'$$), 'ПК видаляє завершене');

-- Після «Відключити» ПК нічого не пише.
select tests.login('pc22');
select ok(tests.ok($$update agents set online = true where uid = 'pc22'$$), 'до відключення ПК пише пульс');
select tests.login('adm62');
select ok(tests.no($$delete from devices where uid = 'pc22'$$), 'чужий адмін не відключає');
select tests.login('emp22');
select ok(tests.no($$delete from devices where uid = 'pc22'$$), 'співробітник не відключає');
select tests.login('pc22');
select ok(tests.ok($$delete from devices where uid = 'pc22'$$), 'ПК відключається сам');
select tests.login('adm22');
select is(tests.run($$delete from devices where uid = 'pc22'$$), 1, 'адмін «Відключити» = delete devices');
select tests.login('pc22');
select ok(tests.no($$update agents set online = true where uid = 'pc22'$$), 'після відключення пульс — ні');
select ok(tests.no($$delete from agents where uid = 'pc22'$$), 'після відключення agents не видаляє');
select ok(tests.no($$update print_jobs set status = 'printing' where agent_id = 'pc22'$$), 'після відключення завдання — ні');

-- ===================== Залишки мережі =====================
select tests.login('emp62');
select ok(tests.ok($$select * from stock where barcode = '4823077601234'$$), 'залишки читає підтверджений');
select ok(tests.ok($$select * from meta_stock$$), 'meta_stock читає підтверджений');
select ok(tests.ok($$select * from stores where code = '22'$$), 'stores читає підтверджений');
select tests.login('new22');
select ok(tests.no($$select * from stock$$), 'непідтверджений залишки не читає');

select tests.login('emp22');
select ok(tests.no($$insert into stock (barcode, q) values ('1', '{"22": 1}')$$), 'співробітник не пише stock');
select ok(tests.no($$select stock_upload_begin('x.xlsx')$$), 'співробітник не починає завантаження');
select tests.login('adm22');
select ok(tests.no($$select stock_upload_begin('x.xlsx')$$), 'адмін магазину не завантажує');
select ok(tests.no($$select store_link('22', '22')$$), 'адмін магазину не прив''язує склад');
select tests.login('stocker');
select ok(tests.no($$insert into stock (barcode, q) values ('1', '{"22": 1}')$$), 'stocker напряму stock не пише');
select ok(tests.no($$insert into stores (code, name) values ('09', 'x')$$), 'stocker напряму stores не пише');
select ok(tests.no($$insert into stock_uploads (by_uid) values ('stocker')$$), 'stocker напряму meta не пише');

-- q — map чисел ≥ 0 (перевірка таблиці; діє і для RPC).
reset role;
select throws_ok($$insert into stock (barcode, q) values ('3', '{"22": -1}')$$, '23514', null, 'q: від''ємне — ні');
select throws_ok($$insert into stock (barcode, q) values ('3', '{"22": "5"}')$$, '23514', null, 'q: рядок — ні');
select throws_ok($$insert into stock (barcode, q) values ('3', '[1, 2]')$$, '23514', null, 'q: масив — ні');
select throws_ok($$insert into stock (barcode, q) values ('3', '{"22": true}')$$, '23514', null, 'q: bool — ні');
select throws_ok($$insert into stock (barcode, q) values ('3', '{"22": null}')$$, '23514', null, 'q: null — ні');
select throws_ok($$insert into stock (barcode, q) values ('3', '{"22": {"a": 1}}')$$, '23514', null, 'q: map — ні');
select throws_ok($$insert into stock (barcode, q) values ('3', '{"22": -0.5}')$$, '23514', null, 'q: -0.5 — ні');
select lives_ok($$insert into stock (barcode, q) values ('7', '{}')$$, 'q: порожній — так');
select lives_ok($$insert into stock (barcode, q) values ('8', '{"22": 0.25, "62": 1e21, "5": 0}')$$, 'q: дробові й великі — так');
select lives_ok($$insert into stock (barcode, q) select '4', jsonb_object_agg(i::text, 1) from generate_series(1, 100) i$$, 'q: 100 магазинів — так');
select throws_ok($$insert into stock (barcode, q) select '6', jsonb_object_agg(i::text, 1) from generate_series(1, 101) i$$, '23514', null, 'q: 101 магазин — ні');
select throws_ok($$insert into stock (barcode, q) select '5', jsonb_object_agg(i::text, 1) || '{"100": -3}' from generate_series(1, 99) i$$, '23514', null, 'q: від''ємне далеко в map — ні');
select throws_ok($$insert into stores (code, name) values ('ЧО 22', 'ЧО 22')$$, '23514', null, 'код складу — лише цифри');
delete from stock where barcode in ('7', '8', '4');

-- ===================== shops =====================
select tests.login('adm22');
select ok(tests.ok($$update shops set code1c = '000000022' where id = '22'$$), 'code1c — адмін свого магазину');
select tests.login('sup');
select ok(tests.ok($$update shops set code1c = '000000062' where id = '62'$$), 'code1c — sup');
select tests.login('emp22');
select ok(tests.no($$update shops set code1c = 'x' where id = '22'$$), 'code1c — не співробітник');
select ok(tests.no($$update shops set region = 'x', address = 'y' where id = '22'$$), 'зведення магазину — не співробітник');
select ok(tests.ok($$update shops set printer_subnets = '{192.0.2.0/24}' where id = '22'$$), 'співробітник дописує підмережу');
select ok(tests.ok($$insert into printers (shop_id, id, model, kind, ip, port, transport) values ('22', 'g', 'Godex G500', 'label_40x24', '192.0.2.11', 9100, 'tcp') on conflict (shop_id, id) do update set ip = excluded.ip, last_seen = now()$$), 'телефон робить upsert принтера');
select ok(tests.no($$update printers set name = 'Каса' where shop_id = '22' and id = 'g'$$), 'назву принтера — не співробітник');
select ok(tests.no($$update printers set density = 10 where shop_id = '22' and id = 'g'$$), 'щільність — не співробітник');
select ok(tests.no($$insert into printers (shop_id, id, model, kind, ip, port, transport, speed) values ('22', 'n', 'Godex', 'label_40x24', '192.0.2.12', 9100, 'tcp', 3)$$), 'швидкість при створенні — не співробітник');
select ok(tests.no($$insert into printers (shop_id, id, model, kind, ip, port, transport) values ('22', 'n', 'Godex', 'зебра', '192.0.2.12', 9100, 'tcp')$$), 'невідомий kind — ні');
select ok(tests.no($$delete from printers where shop_id = '22' and id = 'g'$$), 'співробітник не видаляє принтер');
select tests.login('adm22');
select ok(tests.ok($$update printers set name = 'Каса' where shop_id = '22' and id = 'g'$$), 'назву принтера — адмін');
select tests.login('sup');
select ok(tests.ok($$delete from printers where shop_id = '22' and id = 'g'$$), 'sup видаляє принтер');

-- ===================== adminLog =====================
select tests.login('adm22');
select ok(tests.ok($$insert into admin_log (action, target, shop_id) values ('approve', 'new22', '22')$$), 'adm22 пише журнал');
select ok(tests.no($$insert into admin_log (by_uid, action) values ('sup', 'approve')$$), 'від чужого імені — ні');
select ok(tests.no($$select * from admin_log where shop_id is distinct from '22'$$), 'adm22 не читає журнал інших магазинів');
select tests.login('sup');
select ok(tests.ok($$insert into admin_log (action, target, shop_id) values ('approve', 'new22', '22')$$), 'sup пише журнал');
select ok(tests.ok($$select * from admin_log$$), 'sup читає журнал');
select ok(tests.no($$update admin_log set action = 'x'$$), 'журнал не змінюється');
select ok(tests.no($$delete from admin_log$$), 'журнал не видаляється');
select tests.login('emp22');
select ok(tests.no($$insert into admin_log (action) values ('approve')$$), 'співробітник журнал не пише');

-- ===================== Імпорт залишків (етап 3) =====================
select tests.login('stocker');
select ok(tests.rows($$select * from shops$$) >= 2, 'stocker бачить список магазинів');
select ok(tests.ok($$select stores_upsert('[{"code": "09", "name": "ЧО 09 Біла Церква (ТЦ Гермес)", "city": "Біла Церква"}]')$$), 'stocker пише склади');
select tests.run($$select stores_upsert('[{"code": "09", "name": "ЧО 09 Біла Церква (ТЦ Гермес)", "city": "Біла Церква"}]')$$);
select ok(tests.ok($$select store_link('09', '22')$$), '«Який це магазин?»: прив''язка складу');
select ok(tests.ok($$insert into admin_log (action, details) values ('stockImport', '{"stores": 46}')$$), 'stocker пише stockImport');
select ok(tests.no($$insert into admin_log (action) values ('approve')$$), 'інші дії stocker не пише');

-- Повний цикл: 450 товарів × 46 магазинів.
reset role;
insert into stores (code, name) select lpad(i::text, 2, '0'), 'ЧО ' || i from generate_series(1, 46) i on conflict do nothing;
select tests.login('stocker');
select set_config('t.up', stock_upload_begin('test.xlsx')::text, true);
select is(stock_upload_chunk(current_setting('t.up')::bigint, (
  select jsonb_agg(jsonb_build_object('barcode', (4820000000000 + i)::text, 'name', 'Товар ' || i || ', з комою',
    'code', 'BOC022446', 'article', '',
    'q', (select jsonb_object_agg(lpad(s::text, 2, '0'), (s % 5) + 1) from generate_series(1, 46) s)))
  from generate_series(0, 449) i)), 450, 'пачка 450 товарів');
select throws_ok(format($$select stock_upload_commit(%s, '{01,99}')$$, current_setting('t.up')::bigint), 'P0001', 'unknown_stores', 'невідомий склад — «Який це магазин?»');
reset role;
insert into stock (barcode, q) select 'old' || i, '{"01": 1}' from generate_series(1, 1000) i;
select tests.login('stocker');
select throws_ok(format($$select stock_upload_commit(%s, (select array_agg(lpad(s::text, 2, '0')) from generate_series(1, 46) s))$$, current_setting('t.up')::bigint),
  'P0001', 'confirm_required', '< 50% від минулого — потрібне підтвердження');
reset role;
delete from stock;
select tests.login('stocker');
select is((stock_upload_commit(current_setting('t.up')::bigint, (select array_agg(lpad(s::text, 2, '0')) || '{22,62}'::text[] from generate_series(1, 46) s)) ->> 'items')::int,
  450, 'commit 450 товарів');
select is((select count(*)::int from stock), 450, 'у stock 450 товарів');
select is((select items from meta_stock), 450, 'meta_stock — останнє завантаження');

-- Той самий файл удруге — 0 змін; магазин без товарів у файлі не обнуляється.
reset role;
update stock set q = q || '{"62": 7}' where barcode = '4820000000000';
select tests.login('stocker');
select set_config('t.up2', stock_upload_begin('again.xlsx')::text, true);
select stock_upload_chunk(current_setting('t.up2')::bigint, (
  select jsonb_agg(jsonb_build_object('barcode', (4820000000000 + i)::text, 'name', 'Товар ' || i || ', з комою',
    'code', 'BOC022446', 'article', '',
    'q', (select jsonb_object_agg(lpad(s::text, 2, '0'), (s % 5) + 1) from generate_series(1, 46) s)))
  from generate_series(0, 449) i));
select is((stock_upload_commit(current_setting('t.up2')::bigint, (select array_agg(lpad(s::text, 2, '0')) || '{62}'::text[] from generate_series(1, 46) s)) ->> 'changed')::int,
  0, 'той самий файл удруге — 0 змін');
select is((select (q ->> '62')::int from stock where barcode = '4820000000000'), 7, 'магазин без товарів у файлі не обнуляється');

-- Товару немає у файлі → 0 лише для магазинів із файлу.
select set_config('t.up3', stock_upload_begin('less.xlsx')::text, true);
select stock_upload_chunk(current_setting('t.up3')::bigint, (
  select jsonb_agg(jsonb_build_object('barcode', (4820000000000 + i)::text, 'q', jsonb_build_object('01', 1)))
  from generate_series(1, 449) i));
select is((stock_upload_commit(current_setting('t.up3')::bigint, '{01}', true) ->> 'removed')::int, 0, 'інші магазини лишаються — рядок не видаляється');
select is((select (q ? '01') from stock where barcode = '4820000000000'), false, 'товару немає у файлі → 0 для ЧО 01');
select is((select (q ->> '02')::int from stock where barcode = '4820000000000'), 3, 'для ЧО 02 (не у файлі) — без змін');

-- Запити застосунку.
select tests.login('emp22');
select is((select count(*)::int from stock_in_store('01', 1000)), 449, 'stock_in_store: товари з q > 0 у ЧО 01');
select is((select (q ->> '01')::int from stock_in_store('01', 1) limit 1), 1, 'stock_in_store: найбільший залишок першим');
select is((select count(*)::int from stock_changed_last_import('01')), 449, 'змінені останнім завантаженням');
select tests.login('new22');
select is((select count(*)::int from stock_in_store('01')), 0, 'непідтверджений stock_in_store не бачить');

-- Вигрузка 1С (service_role): без назв — назви лишаються.
reset role;
select set_config('request.jwt.claims', '{"role": "service_role"}', true);
select set_config('role', 'service_role', true);
select set_config('t.up4', stock_upload_begin('stock.csv', 'csv')::text, true);
select stock_upload_chunk(current_setting('t.up4')::bigint, '[{"barcode": "4820000000001", "article": "", "q": {"02": 9}}]');
select is((stock_upload_commit(current_setting('t.up4')::bigint, '{02}', true) ->> 'stores')::int, 1, 'service_role завантажує');
select is((select name from stock where barcode = '4820000000001'), 'Товар 1, з комою', 'CSV без назв: назва лишилась');
select is((select (q ->> '02')::int from stock where barcode = '4820000000001'), 9, 'CSV: кількість оновлена');
select is((select by_uid from stock_uploads where id = current_setting('t.up4')::bigint), 'service:1c', 'хто завантажив: service:1c');
reset role;

-- ===================== Черга друку (pg_cron) =====================
insert into devices (uid, shop_id, name, approved_by) values ('pcM', '22', 'M', 'adm22');
insert into print_jobs (id, agent_id, printer, shop_id, by_uid, status, data, attempts, started_at, done_at, created_at) values
  ('00000000-0000-0000-0000-0000000000a1', 'pcM', 'G', '22', 'emp22', 'printing', '\x01', 1, now() - interval '3 min', null, now()),
  ('00000000-0000-0000-0000-0000000000a2', 'pcM', 'G', '22', 'emp22', 'printing', '\x01', 2, now() - interval '3 min', null, now()),
  ('00000000-0000-0000-0000-0000000000a3', 'pcM', 'G', '22', 'emp22', 'printing', '\x01', 1, now() - interval '1 min', null, now()),
  ('00000000-0000-0000-0000-0000000000a4', 'pcM', 'G', '22', 'emp22', 'done', '\x01', 1, null, now() - interval '8 days', now() - interval '8 days');
select app.print_jobs_maintain();
select is((select status from print_jobs where id = '00000000-0000-0000-0000-0000000000a1'), 'queued', 'зависле > 2 хв — знову в черзі');
select is((select status from print_jobs where id = '00000000-0000-0000-0000-0000000000a2'), 'error', 'зависле вдруге — error');
select is((select status from print_jobs where id = '00000000-0000-0000-0000-0000000000a3'), 'printing', 'друкується < 2 хв — не чіпаємо');
select is((select count(*)::int from print_jobs where id = '00000000-0000-0000-0000-0000000000a4'), 0, 'done старше 7 днів — видалено');
select isnt((select jobid from cron.job where jobname = 'print-jobs-maintain'), null, 'pg_cron: щохвилини');

-- ===================== «Працювати як у магазині» =====================
select tests.login('sup');
select set_config('request.headers', '{"x-act-shop": "62"}', true);
select is(acting_shop(), '62', 'sup працює як у ЧО 62');
select tests.login('emp22');
select is(acting_shop(), '22', 'співробітник заголовком магазин не змінює');
reset role;
select is((select shop_id from users where uid = 'sup'), '1', 'users.shop_id у sup не змінився');

-- ===================== Новинки й акції =====================
select tests.login('stocker');
select is(publish_product_highlights('new',
  '[{"barcode":"0012345678901","article":"A1","name":"Гра"}]'), 1, 'адмін публікує новинки');
select is(publish_product_highlights('promo',
  '[{"barcode":"0012345678901","article":"A1","name":"Гра","promo_price":199.50},
    {"article":"EXPIRED","name":"Минуле","ends_on":"2000-01-01"},
    {"article":"FUTURE","name":"Майбутнє","starts_on":"2099-01-01"}]'), 3, 'адмін публікує окремий список акцій');
select is(tests.rows($q$select publish_product_highlights('promo',
  '[{"article":"BAD","name":"Дублікат"},{"article":"BAD","name":"Дублікат"}]')$q$),
  -1, 'дублікати блокують публікацію');

select tests.login('emp22');
select is((select count(*)::int from list_product_highlights('promo')), 1, 'консультант бачить лише чинні акції після невдалого імпорту');
select is((select count(*)::int from product_highlights_for('0012345678901', '')),
  2, 'скан повертає обидві позначки');
select is((select count(*)::int from product_highlights_for('', 'a1')),
  2, 'пошук артикула не залежить від регістру');
select is(tests.rows($q$select publish_product_highlights('new',
  '[{"article":"NEW","name":"Товар"}]')$q$), -1, 'консультант не публікує');
select is(tests.rows($q$delete from product_highlights$q$), -1, 'прямий запис заборонено');
select tests.login('new22');
select is((select count(*)::int from list_product_highlights('new')), 0, 'непідтверджений працівник не бачить список');
select tests.login('emp22', 'foreign');
select is((select count(*)::int from list_product_highlights('new')), 0, 'чужий Firebase проєкт не бачить список');
reset role;

-- Server-generated audit: only an approved network admin may read it, and no
-- authenticated client can write or erase events.
select tests.login('emp22');
select set_config('request.headers', '{"x-forwarded-for":"198.51.100.2"}', true);
select tests.run($q$update users set name = 'Audit check' where uid = 'emp22'$q$);
select is((select count(*)::int from security_events), 0, 'employees cannot read security events');
select tests.login('adm22');
select is((select count(*)::int from security_events), 0, 'shop admins cannot read security events');
select tests.login('sup', 'foreign');
select is((select count(*)::int from security_events), 0, 'foreign issuer cannot read security events');
select tests.login('sup');
select is((select count(*)::int from security_events
           where action = 'account.profile_changed' and target = 'emp22' and by_uid = 'emp22' and ip is null),
          1, 'server records the actor and ignores the client-supplied IP');
select is(tests.rows($q$insert into security_events(action, target) values ('session.allowed', 'emp22')$q$),
          -1, 'client cannot forge a security event');
select is(tests.rows($q$delete from security_events$q$), -1, 'client cannot erase security events');
reset role;

-- Device enrollment must be approved before a challenge can be issued.
select is(app.request_device_enrollment('emp22', 1, decode('04' || repeat('01', 64), 'hex'), '192.0.2.1'),
  'pending', 'first device waits for an administrator');
select is(app.issue_device_challenge('emp22', 1, decode(repeat('aa', 32), 'hex')),
  null::bigint, 'pending device cannot request a challenge');
select tests.login('emp22');
select is((select count(*)::int from device_access), 0, 'employee cannot see device IP');
select is(decide_device_access(gen_random_uuid(), 'approve'), false, 'employee cannot approve a device');
select tests.login('adm22');
select is((select count(*)::int from device_access), 0, 'shop admin cannot see device IP');
select tests.login('sup');
select is((select request_ip from device_access where uid = 'emp22'), '192.0.2.1', 'network admin sees the gateway IP');
select ok(decide_device_access((select id from device_access where uid = 'emp22'), 'approve'),
  'network admin approves the exact key');
reset role;
select isnt(app.issue_device_challenge('emp22', 1, decode(repeat('aa', 32), 'hex')),
  null::bigint, 'approved device can request a challenge');
-- This privileged test call stands in for a successful Rust signature check.
select set_config('tests.old_device_session',
  app.finish_device_challenge('emp22', 1, decode(repeat('aa', 32), 'hex'), '192.0.2.2')::text, true);
select ok(app.device_session_valid('emp22', 1, current_setting('tests.old_device_session')::uuid), 'first session is active');
insert into app.device_required values ('emp22'), ('emp62');
select set_config('request.headers', '{}', true);
select tests.login('emp22');
select is(app.firebase_ok(), false, 'pilot account cannot use Firebase token alone');
select is((select uid from device_login_profile()), 'emp22', 'pilot account can read its own enrollment profile');
select is((select count(*)::int from stock), 0, 'pilot account without device session cannot read stock');
select set_config('request.headers', jsonb_build_object('x-device-session', current_setting('tests.old_device_session'))::text, true);
select is(app.firebase_ok(), true, 'active Android session authorizes a request for the same account');
select tests.login('emp62');
select is(app.firebase_ok(), false, 'active session cannot be reused by another Firebase account');
reset role;
select is(app.request_device_enrollment('emp22', 1, decode('04' || repeat('02', 64), 'hex'), '192.0.2.3'),
  'pending', 'second key still awaits approval');
select ok(app.device_session_valid('emp22', 1, current_setting('tests.old_device_session')::uuid),
  'pending replacement does not interrupt the existing session');
select tests.login('sup', 'foreign');
select is((select count(*)::int from device_access), 0, 'foreign issuer cannot read device information');
select tests.login('sup');
select is((select last_ip from device_access where uid = 'emp22' and status = 'approved'), '192.0.2.2',
  'network admin sees the verified session IP');
select ok(decide_device_access((select id from device_access where uid = 'emp22' and status = 'pending'), 'approve'),
  'network admin approves the replacement');
reset role;
select is(app.device_session_valid('emp22', 1, current_setting('tests.old_device_session')::uuid), false,
  'replacement revokes the old session');
select tests.login('emp22');
select is(app.firebase_ok(), false, 'protected API rejects the revoked device session');
select tests.login('sup');
select ok(decide_device_access((select id from device_access where uid = 'emp22' and status = 'approved'), 'block'),
  'network admin can block an approved key');
reset role;
select is(app.request_device_enrollment('emp22', 1, decode('04' || repeat('02', 64), 'hex'), '192.0.2.4'),
  'blocked', 'blocked key cannot re-enroll from a different IP');

-- ===================== Запити на скидання пароля =====================
reset role;
select set_config('request.headers', '{}', true);
delete from app.device_required;
update users set phone = '+380671234567' where uid = 'emp22';
update users set phone = '+380671234568' where uid = 'emp62';
select tests.anon();
select is(request_password_reset('+380671234567', '22'), 'ok', 'анонім створює запит із номером і магазином');
select is(request_password_reset('+380671234567', '22'), 'too_soon', 'повторний запит на номер за 10 хв не створюється');
select is(request_password_reset('0671234567', '22'), 'bad_phone', 'номер не у форматі +380XXXXXXXXX');
select is(request_password_reset('+380671234569', '999'), 'bad_shop', 'неіснуючий магазин');
select is(request_password_reset('+380500000000', '22'), 'ok', 'невідомий номер — та сама відповідь (номери не перебрати)');
select is(request_password_reset('+380671234568', '22'), 'ok', 'запит на співробітника ЧО 62 від імені ЧО 22');
select ok(tests.no($$select * from password_requests$$), 'анонім не читає запити');
select ok(tests.no($$insert into password_requests (phone, shop_id) values ('+380671111111', '22')$$), 'анонім не пише в таблицю напряму');

select tests.login('emp22');
select ok(tests.no($$select * from password_requests$$), 'співробітник не бачить запитів');
select is((select count(*)::int from password_requests_for('22')), 0, 'співробітник не отримує список');
select tests.login('adm62');
select ok(tests.no($$select * from password_requests where shop_id = '22'$$), 'адмін ЧО 62 не бачить запитів ЧО 22');
select throws_ok(format('select password_reset_target(%L)', (select id from password_requests where phone = '+380671234567')),
  '42501', null, 'адмін ЧО 62 не скидає пароль у ЧО 22');
select tests.login('adm22');
select is((select count(*)::int from password_requests where shop_id = '22'), 3, 'адмін ЧО 22 бачить запити свого магазину');
select is((select name from password_requests_for('22') where phone = '+380671234567'), (select name from users where uid = 'emp22'), 'у списку — ім’я з анкети');
select is(password_reset_target((select id from password_requests where phone = '+380671234567')), 'emp22', 'адмін ЧО 22 скидає пароль свого співробітника');
select throws_ok(format('select password_reset_target(%L)', (select id from password_requests where phone = '+380671234568')),
  'P0002', null, 'співробітника іншого магазину не скинути, навіть якщо запит у своєму');
select throws_ok(format('select password_reset_target(%L)', (select id from password_requests where phone = '+380500000000')),
  'P0002', null, 'невідомий номер — нікого скидати');
select lives_ok(format('select password_reset_finish(%L)', (select id from password_requests where phone = '+380671234567')),
  'скидання закриває запит');
select is((select status from password_requests where phone = '+380671234567'), 'done', 'запит закрито');
select throws_ok(format('select password_reset_target(%L)', (select id from password_requests where phone = '+380671234567')),
  'P0001', null, 'закритий запит удруге не скинути');
select ok(tests.ok($$update password_requests set status = 'rejected', done_by = 'adm22', done_at = now() where phone = '+380500000000'$$),
  'адмін відхиляє запит свого магазину');
select ok(tests.no($$update password_requests set status = 'done', done_by = 'adm22' where phone = '+380500000000'$$),
  'адмін не ставить «done» в обхід скидання');
select tests.login('emp22');
select is((select must_change_password from users where uid = 'emp22'), true, 'після скидання — треба змінити пароль');
select ok(tests.no($$update users set must_change_password = false where uid = 'emp22'$$), 'позначку не зняти напряму');
select tests.login('emp62');
select lives_ok('select password_changed()', 'чужий виклик нічого не ламає');
select tests.login('emp22');
select is((select must_change_password from users where uid = 'emp22'), true, 'чужий виклик не знімає позначку emp22');
select lives_ok('select password_changed()', 'співробітник повідомляє, що змінив пароль');
select is((select must_change_password from users where uid = 'emp22'), false, 'позначку знято');
select tests.anon();
select throws_ok('select password_changed()', '42501', null, 'анонім не викликає');

select tests.login('sup');
select is((select count(*)::int from admin_log where action = 'passwordReset' and target = 'emp22'), 1, 'скидання — у журналі дій');

-- ===================== Посади й колектив магазину =====================
reset role;
select set_config('request.headers', '{}', true);
delete from app.device_required;
select tests.login('emp22');
select ok(tests.no($$update users set job_title = 'manager' where uid = 'emp22'$$), 'співробітник не ставить собі посаду');
select tests.login('adm62');
select ok(tests.no($$update users set job_title = 'senior' where uid = 'emp22'$$), 'адмін ЧО 62 не ставить посаду в ЧО 22');
select tests.login('adm22');
select ok(tests.no($$update users set job_title = 'boss' where uid = 'emp22'$$), 'невідома посада не пройде');
select ok(tests.ok($$update users set job_title = 'senior' where uid = 'emp22'$$), 'адмін ЧО 22 ставить посаду співробітнику');
select lives_ok($$update users set job_title = 'manager' where uid = 'adm22'$$, 'адмін ставить посаду собі');
select tests.login('emp22');
select is((select count(*)::int from shop_team()), 2, 'співробітник бачить колектив: лише підтверджених свого магазину');
select is((select job_title from shop_team() where uid = 'emp22'), 'senior', 'у колективі — посада');
select is((select admin from shop_team() where uid = 'adm22'), true, 'у колективі видно адміна');
select ok(not exists (select 1 from shop_team() where uid in ('new22', 'emp62')), 'без непідтверджених і чужих');
select tests.login('new22');
select throws_ok('select * from shop_team()', '42501', null, 'непідтверджений колектив не бачить');
select tests.login('sup');
select set_config('request.headers', '{"x-act-shop":"22"}', true);
select is((select count(*)::int from shop_team()), 2, 'адмін мережі бачить колектив магазину, в якому працює');
select set_config('request.headers', '{}', true);
select tests.anon();
select throws_ok('select * from shop_team()', '42501', null, 'анонім не бачить колектив');

-- ===================== Журнал дій свого магазину =====================
reset role;
select set_config('request.headers', '{}', true);
delete from app.device_required;
insert into admin_log (by_uid, action, target, shop_id) values ('sup', 'approve', 'new62', '62'), ('sup', 'approve', 'new22', '22');
select tests.login('adm22');
select ok(tests.ok($$select * from admin_log where shop_id = '22'$$), 'адмін ЧО 22 читає журнал свого магазину');
select ok(tests.no($$select * from admin_log where shop_id = '62'$$), 'адмін ЧО 22 не читає журнал ЧО 62');
select ok(tests.ok($$insert into admin_log (action, target, shop_id) values ('disable', 'new22', '22')$$), 'адмін пише про свій магазин');
select ok(tests.no($$insert into admin_log (action, target, shop_id) values ('disable', 'new62', '62')$$), 'адмін не пише в журнал чужого магазину');
select tests.login('emp22');
select ok(tests.no($$select * from admin_log where shop_id = '22'$$), 'співробітник журнал не читає');
select tests.login('sup');
select ok(tests.ok($$select * from admin_log where shop_id = '62'$$), 'адмін мережі читає весь журнал');

select * from finish();
rollback;
