const by = (key, label, get = row => row[key]) => ({ key, label, get });
const total = row => Object.values(row.q ?? {}).reduce((sum, qty) => sum + qty, 0);

export const adminSections = {
  products: {
    title: 'Товари', table: 'stock', search: 'name', order: 'barcode',
    columns: [by('name', 'Товар'), by('article', 'Артикул'), by('barcode', 'Штрихкод'), by('code', 'Код 1С'), by('q', 'У мережі, шт', total)],
  },
  shops: {
    title: 'Магазини', table: 'shops', search: 'address', order: 'id',
    columns: [by('address', 'Адреса'), by('region', 'Регіон'), by('code1c', 'Склад 1С')],
  },
  staff: {
    title: 'Співробітники', table: 'users', search: 'name', order: 'uid',
    columns: [by('name', 'Співробітник'), by('shop', 'Магазин'), by('admin', 'Роль', staffRole),
      by('approved', 'Статус', row => row.approved ? 'Активний' : 'Очікує')],
    fields: ['uid', 'phone', 'stock_admin', 'super_admin'],
  },
  printers: {
    title: 'Принтери', table: 'printers', search: 'model', order: 'shop_id', tie: 'id',
    columns: [by('name', 'Назва'), by('model', 'Модель'), by('kind', 'Формат', row => row.kind === 'a4' ? 'A4' : '40×24'), by('transport', 'Підключення')],
  },
  devices: {
    title: "Комп'ютери", table: 'devices', search: 'name', order: 'uid',
    columns: [by('name', 'Назва'), by('shop_id', 'Магазин'), by('paired_at', 'Підключено')],
  },
  deviceAccess: {
    title: 'Доступ пристроїв', table: 'device_access', search: 'name', order: 'requested_at', tie: 'id', descending: true,
    columns: [by('id', 'Запит'), by('name', "Ім’я"), by('uid', 'Обліковий запис'),
      by('purpose', 'Тип', row => ({ 1: 'Android', 2: 'Адмінка', 3: 'Принтер' })[row.purpose]),
      by('status', 'Стан', row => ({ pending: 'Очікує', approved: 'Підтверджено', blocked: 'Заблоковано', replaced: 'Замінено' })[row.status]),
      by('requested_at', 'Подано'), by('request_ip', 'IP запиту'), by('last_ip', 'IP останнього входу'), by('seen_at', 'Останній вхід')],
  },
  security: {
    title: 'Події безпеки', table: 'security_events', search: 'action', order: 'id', descending: true,
    columns: [by('at', 'Час'), by('by_uid', 'Обліковий запис'), by('action', 'Подія'), by('target', 'Запис'),
      by('shop_id', 'Магазин'), by('ip', 'IP'), by('outcome', 'Результат')],
  },
  audit: {
    title: 'Журнал дій', table: 'admin_log', search: 'action', order: 'id', descending: true,
    columns: [by('at', 'Час'), by('action', 'Дія'), by('target', 'Запис'), by('shop_id', 'Магазин')],
  },
};

export const PAGE_SIZE = 30;

function staffRole(row) {
  if (row.super_admin) return 'Адмін мережі';
  if (row.stock_admin) return 'Адмін залишків';
  if (row.admin) return 'Адмін магазину';
  return 'Консультант';
}

export function canViewSection(key, account) {
  if (['audit', 'security', 'deviceAccess'].includes(key)) return account.superAdmin;
  if (key === 'history' || ['stock', 'new', 'promo'].includes(key)) return account.canImport;
  if (['staff', 'printers', 'devices'].includes(key)) return account.superAdmin || account.shopAdmin;
  return account.canImport || account.shopAdmin;
}

export function adminQuery(client, key, search, offset, status = '') {
  const section = adminSections[key];
  let query = client.from(section.table).select([...section.columns.map(column => column.key), ...(section.fields ?? [])].join(','));
  if (search) {
    const pattern = '%' + search.replace(/[\\%_]/g, '\\$&') + '%';
    if (key === 'staff') {
      const operand = '"' + pattern.replace(/[\\"]/g, '\\$&') + '"';
      query = query.or(['name', 'phone', 'shop'].map(field => field + '.ilike.' + operand).join(','));
    } else query = query.ilike(section.search, pattern);
  }
  if (key === 'staff' && ['pending', 'approved'].includes(status)) query = query.eq('approved', status === 'approved');
  if (key === 'deviceAccess' && ['pending', 'approved', 'blocked'].includes(status)) query = query.eq('status', status);
  query = query.order(section.order, { ascending: !section.descending });
  if (section.tie) query = query.order(section.tie);
  return query.range(offset, offset + PAGE_SIZE);
}
