const columns = {
  barcode: ['штрихкод', 'штрих-код', 'barcode', 'ean'],
  article: ['артикул', 'article', 'sku'],
  name: ['назва', 'товар', 'найменування', 'наименование', 'номенклатура', 'name'],
  starts_on: ['початок', 'дата початку', 'дата начала', 'starts_on'],
  ends_on: ['кінець', 'дата завершення', 'дата окончания', 'ends_on'],
  promo_price: ['акційна ціна', 'акционная цена', 'promo_price'],
};

function date(value) {
  if (!value) return null;
  const parts = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(value);
  let iso = parts ? [parts[3], parts[2], parts[1]].join('-') : value;
  if (/^\d{1,5}$/.test(value) && Number(value) >= 61) {
    iso = new Date(Date.UTC(1899, 11, 30) + Number(value) * 86400000).toISOString().slice(0, 10);
  }
  const parsed = new Date(iso + 'T00:00:00Z');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso) || iso.startsWith('0000-')
      || !Number.isFinite(+parsed) || parsed.toISOString().slice(0, 10) !== iso) {
    throw new Error('некоректна дата');
  }
  return iso;
}

export function highlightReport(kind) {
  let header;
  const rows = [];
  const keys = new Set();
  return {
    row(index, cells) {
      const values = cells.map((value) => (value ?? '').trim());
      if (!header) {
        if (index > 40) throw new Error('Не знайдено заголовки «Назва» та «Штрихкод» або «Артикул».');
        const normalized = values.map((value) => value.toLowerCase());
        const positions = Object.fromEntries(Object.entries(columns).map(([key, aliases]) => [
          key, normalized.findIndex((value) => aliases.includes(value)),
        ]));
        if (positions.name >= 0 && (positions.barcode >= 0 || positions.article >= 0)) header = positions;
        return;
      }
      if (!values.some(Boolean)) return;
      const get = (key) => values[header[key]] ?? '';
      try {
        const barcode = get('barcode');
        const article = get('article');
        const name = get('name');
        if ((!barcode && !article) || (barcode && !/^\d{8,14}$/.test(barcode))
          || article.length > 100 || !name || name.length > 300) throw new Error('перевірте назву, штрихкод та артикул');
        const starts_on = date(get('starts_on'));
        const ends_on = date(get('ends_on'));
        if (starts_on && ends_on && starts_on > ends_on) throw new Error('кінець раніше початку');
        const rawPrice = get('promo_price').replace(',', '.');
        if (kind === 'promo' && rawPrice && !/^\d{1,10}(?:\.\d{1,2})?$/.test(rawPrice)) {
          throw new Error('некоректна акційна ціна');
        }
        const key = JSON.stringify([barcode, article]);
        if (keys.has(key)) throw new Error('товар дублюється');
        if (rows.length === 10000) throw new Error('звіт має містити до 10 000 товарів');
        keys.add(key);
        rows.push({ barcode, article, name, starts_on, ends_on, promo_price: kind === 'promo' && rawPrice ? Number(rawPrice) : null });
      } catch (error) {
        throw new Error(`Рядок ${index}: ${error.message}.`);
      }
    },
    finish() {
      if (!rows.length) throw new Error('У звіті немає товарів. Потрібні «Назва» та «Штрихкод» або «Артикул».');
      return rows;
    },
  };
}
