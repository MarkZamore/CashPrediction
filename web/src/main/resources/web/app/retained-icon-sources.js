/** @file Сохранение первоначального HTTP-ответа значка до установки настоящего источника изображения. */

const LIMIT = 1024 * 1024;

/**
 * Создаёт ограниченный установщик для явно разрешённых сервером общих PNG.
 * Реестр получает именно байты первого ответа, а не позднюю загрузку currentSrc.
 * Загрузка не выбирает значок по DOM-маркеру и не подтверждает завершение краски.
 * @param {object} options реестр, белый список URL и штатный fetch текущей вкладки
 * @returns {object} установщик с явной проверкой завершения и ошибок
 */
export function createRetainedIconSources({registry, urls, fetcher = globalThis.fetch, timeoutMs = 5000}) {
  if (!registry || typeof registry.installImage !== 'function' || typeof registry.installBackground !== 'function'
      || !Array.isArray(urls) || !Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 30000)
    throw new Error('Icon source configuration');
  const allowed = new Set(urls.map(/** Приводит только разрешённый адрес общего PNG к абсолютной форме. */ value => {
    const url = new URL(value, document.baseURI);
    if (url.origin !== location.origin || !/^https?:$/.test(url.protocol) || url.search || url.hash
        || !/^\/app\/icons\/[^/]+\.png$/.test(url.pathname)) throw new Error('Icon source URL');
    return url.href;
  }));
  const cache = new Map(), bindings = new WeakMap(), pending = new Set(), failures = [], controllers = new Set();
  let disposed = false;

  /** Читает первоначальный PNG-ответ с лимитом независимо от заявленного Content-Length. */
  async function load(url) {
    const controller = new AbortController(); controllers.add(controller);
    const timeout = setTimeout(/** Прерывает только принадлежащую установщику загрузку после её дедлайна. */ () => controller.abort(), timeoutMs);
    let reader;
    try {
      const response = await fetcher(url, {redirect: 'error', credentials: 'same-origin', signal: controller.signal});
      if (!response.ok || response.status !== 200 || new URL(response.url).href !== url
          || response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'image/png')
        throw new Error('Icon source response');
      const length = response.headers.get('Content-Length');
      if (length !== null && (!/^[0-9]+$/.test(length) || Number(length) > LIMIT)) throw new Error('Icon source limit');
      if (!response.body) throw new Error('Icon source body');
      reader = response.body.getReader();
      const chunks = []; let size = 0;
      while (true) {
        const next = await reader.read();
        if (next.done) break;
        if (!(next.value instanceof Uint8Array) || (size += next.value.length) > LIMIT) throw new Error('Icon source limit');
        chunks.push(next.value.slice());
      }
      if (!size || (length !== null && Number(length) !== size)) throw new Error('Icon source length');
      const bytes = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      if (![137, 80, 78, 71, 13, 10, 26, 10].every(/** Сверяет фактическую сигнатуру ответа PNG. */ (byte, index) => bytes[index] === byte))
        throw new Error('Icon source signature');
      return bytes;
    } catch (error) {
      if (reader) { try { await reader.cancel(); } catch { /* Исходная ошибка остаётся причиной отказа. */ } }
      throw error;
    } finally { clearTimeout(timeout); controllers.delete(controller); reader?.releaseLock(); }
  }

  /** Загружает адрес один раз; копии исходных байтов принадлежат отдельным декодированиям. */
  function bytesFor(source) {
    const url = new URL(source, document.baseURI).href;
    if (disposed || !allowed.has(url)) throw new Error('Unapproved icon source');
    if (!cache.has(url)) cache.set(url, load(url));
    return {url, bytes: cache.get(url)};
  }

  /** Устанавливает только последнее назначение конкретного узла, не подменяя более свежий источник. */
  function install(node, source, background) {
    const {url, bytes} = bytesFor(source), ticket = {}; bindings.set(node, ticket);
    const task = (async () => {
      const original = await bytes;
      if (disposed || bindings.get(node) !== ticket) return;
      try {
        if (background) await registry.installBackground(node, original.slice(), url);
        else await registry.installImage(node, original.slice(), url);
      } catch (error) {
        // Прерванное более новым назначением декодирование не считается ошибкой текущего узла.
        if (!disposed && bindings.get(node) === ticket) throw error;
      }
    })();
    pending.add(task);
    task.catch(/** Сохраняет отказ для явного fail-closed барьера, не создавая unhandled rejection. */ error => {
      if (!disposed && bindings.get(node) === ticket) failures.push(error);
    }).finally(/** Удаляет только завершённую операцию из множества активных установок. */ () => pending.delete(task));
    return task;
  }

  return {
    /** Назначает настоящий img из сохранённых первоначальных байтов HTTP-ответа. */
    installImage(node, source) { return install(node, source, false); },
    /** Назначает настоящий CSS-фон из тех же байтов, которые удерживаются для проверки. */
    installBackground(node, source) { return install(node, source, true); },
    /** Читает число незавершённых назначений, не принимая очередь за завершённую краску. */
    pending() { return pending.size; },
    /** Отказывает при любой ошибке актуального источника; ошибка не стирается последующим успехом. */
    requireHealthy() { if (disposed) throw new Error('Icon sources disposed'); if (failures.length) throw failures[0]; },
    /** Дожидается текущих и появившихся за время ожидания назначений; не подтверждает кадр. */
    async ready() {
      while (pending.size) { await Promise.allSettled([...pending]); this.requireHealthy(); }
      this.requireHealthy();
    },
    /** Отменяет только собственные запросы; освобождение blob URL остаётся обязанностью реестра. */
    dispose() { disposed = true; for (const controller of controllers) controller.abort(); cache.clear(); }
  };
}
