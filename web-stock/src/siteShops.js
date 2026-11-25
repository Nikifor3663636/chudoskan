// Магазини мережі з сайту — той самий список, з якого продавець обирає магазин при реєстрації,
// і ті самі id (AuthRepository.shopId), що в застосунку. Сайт не віддає CORS — йдемо через proxy/.

/** Міста з core/src/regions.rs — змінюючи там, зміни й тут. */
export const REGIONS = [
  ['brovari', 'Бровари'], ['bucha', 'Буча'], ['vinnica', 'Вінниця'], ['dnipro', 'Дніпро'], ['zhitomir', 'Житомир'],
  ['ivano-frankivsk', 'Івано-Франківськ'], ['izmayil', 'Ізмаїл'], ['kam-janec-podilskij', "Кам'янець-Подільський"],
  ['kam-janske', "Кам'янське"], ['kyiv', 'Київ'], ['kovel', 'Ковель'], ['kolomija', 'Коломия'], ['kropivnickij', 'Кропивницький'],
  ['luck', 'Луцьк'], ['lviv', 'Львів'], ['odesa', 'Одеса'], ['oleksandriya', 'Олександрія'], ['poltava', 'Полтава'],
  ['rivne', 'Рівне'], ['stryi', 'Стрий'], ['ternopil', 'Тернопіль'], ['uzhgorod', 'Ужгород'], ['uman', 'Умань'],
  ['harkiv', 'Харків'], ['hmelniickij', 'Хмельницький'], ['cherkasi', 'Черкаси'], ['chernivci', 'Чернівці'],
  ['chernigiv', 'Чернігів'], ['chervonohrad', 'Шептицький'],
];

export const PROXY = 'https://chudoskan-proxy.chudoskan.workers.dev';

/** «вул. Тест,  1» → «вул. Тест, 1» (як split_whitespace у core/src/shops.rs). */
export const squash = (s) => s.split(/\s+/).filter(Boolean).join(' ');

/** id магазину: «{місто}_{перші 12 hex SHA-1 адреси в нижньому регістрі}» — як AuthRepository.shopId. */
export async function shopId(region, address) {
  const norm = squash(address.trim()).toLowerCase();
  const hash = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(norm));
  const hex = [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${region}_${hex.slice(0, 12)}`;
}

/** Адреси з відповіді `{html}` сайту: текст `.shops-item .text`. */
export function addressesFromHtml(html) {
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
  const out = [];
  for (const item of doc.querySelectorAll('.shops-item')) {
    const a = squash(item.querySelector('.text')?.textContent ?? '');
    if (a && !out.includes(a)) out.push(a);
  }
  return out;
}

/** Усі магазини з сайту: [{id, region, address}], region — назва міста. Місто, що не відповіло, пропускаємо. */
export async function loadSiteShops(onProgress = () => {}) {
  const out = [];
  let done = 0;
  for (let i = 0; i < REGIONS.length; i += 6) {
    const chunk = REGIONS.slice(i, i + 6);
    const lists = await Promise.all(chunk.map(async ([code, name]) => {
      try {
        const r = await fetch(`${PROXY}/chudo-ostriv.com.ua/ua/multiwarehouse/shops/index/?id=1&region=${code}`, {
          headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
          // Місто, що не відповіло за 15 с, пропускаємо — інакше вся сторінка чекала б без кінця.
          signal: AbortSignal.timeout(15_000),
        });
        if (!r.ok) return [];
        const html = (await r.json())?.html ?? '';
        return Promise.all(addressesFromHtml(html).map(async (a) => ({ id: await shopId(code, a), region: name, address: a })));
      } catch { return []; }
    }));
    out.push(...lists.flat());
    done += chunk.length;
    onProgress(done / REGIONS.length);
  }
  return out;
}
