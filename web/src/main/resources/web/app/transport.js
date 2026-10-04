/** @file Последовательный транспорт с доказательством сервера при восстановлении связи. */

/** Создаёт техническую отмену старого продолжения. */
function cancelled() { return new DOMException('Obsolete transport generation', 'AbortError'); }
/** Проверяет каноническую запись протокола. */
function hex(value, length) { return typeof value === 'string' && value.length === length && /^[0-9a-f]+$/.test(value); }
/** Преобразует байты в каноническую запись. */
function toHex(bytes) { return Array.from(bytes, /** Записывает байт двумя шестнадцатеричными цифрами с ведущим нулём. */ byte => byte.toString(16).padStart(2, '0')).join(''); }

/** Хранит очередь намерений и секрет переподключения только внутри вкладки. */
export class Transport {
  /** Сохраняет обработчики эффектов и восстановления связи. */
  constructor(apply, disconnected, resync) {
    this.tab = crypto.randomUUID(); this.seq = 0;
    this.apply = apply; this.disconnected = disconnected; this.resync = resync;
    this.tail = Promise.resolve(); this.effectTail = Promise.resolve(); this.pending = 0;
    this.stopped = false; this.generation = 0; this.connected = true;
    this.abort = new AbortController(); this.recovering = null; this.polling = null;
    this.snapshotGenerations = new WeakMap();
    this.credential = null; this.serverGeneration = null; this.authenticatedGeneration = null;
    const params = new URLSearchParams(location.search); this.token = params.get('t') || '';
    try {
      this.token ||= sessionStorage.getItem('cashprediction.token') || '';
      if (this.token) sessionStorage.setItem('cashprediction.token', this.token);
      const stored = JSON.parse(sessionStorage.getItem('cashprediction.reconnect') || 'null');
      if (this.validCredential(stored)) this.credential = Object.freeze(stored);
    } catch { /* Повреждённый или закрытый storage не блокирует начальный bootstrap. */ }
    if (params.has('t')) {
      params.delete('t');
      history.replaceState(null, '', location.pathname + (params.size ? '?' + params : '') + location.hash);
    }
  }

  /** Проверяет дополнительные данные, не требуя их для первого запуска. */
  validCredential(value) { return value?.version === 1 && hex(value.installationId, 32) && hex(value.key, 64); }
  /** Проверяет принадлежность продолжения живому поколению. */
  current(generation) { return !this.stopped && generation === this.generation; }
  /** Проверяет поколение до изменения состояния. */
  requireCurrent(generation) { if (!this.current(generation)) throw cancelled(); }
  /** Возвращает поколение конкретного ответа, не добавляя его в сериализуемый bootstrap. */
  snapshotGeneration(data) { return this.snapshotGenerations.get(data); }
  /** Отменяет старые запросы ровно один раз на одну потерю связи. */
  lost(error, generation = this.generation) {
    if (error?.name === 'AbortError') return;
    if (!this.current(generation) || !this.connected) return;
    this.connected = false; this.generation++; this.authenticatedGeneration = null;
    this.abort.abort(); this.abort = new AbortController();
    this.disconnected(error);
  }

  /** Выполняет обычный запрос без повторного исполнения намерения. */
  async request(path, body, generation = this.generation) {
    this.requireCurrent(generation);
    try {
      const response = await fetch(path, {
        method: body === undefined ? 'GET' : 'POST', cache: 'no-store', redirect: 'error', signal: this.abort.signal,
        headers: {'X-Token': this.token, ...(body === undefined ? {} : {'Content-Type': 'application/json'})},
        body: body === undefined ? undefined : JSON.stringify(body)
      });
      this.requireCurrent(generation);
      const data = await response.json(); this.requireCurrent(generation);
      if (response.status === 503 && data.busy) return {effects: []};
      if (!response.ok) {
        const error = new Error('HTTP ' + response.status); error.status = response.status;
        if (response.status === 403 || response.status >= 500) this.lost(error, generation);
        throw error;
      }
      return data;
    } catch (error) {
      if (error.name !== 'AbortError' && !error.status) this.lost(error, generation);
      throw error;
    }
  }

  /** Получает целостный снимок и сбрасывает курсор только по свежему ответу. */
  async bootstrap() {
    if (this.stopped) throw cancelled();
    // Повторный bootstrap не может обойти доказательство сервера, даже при ручном resync или reload.
    // recover уже проверяет текущее поколение; его доказательство используем один раз без второго handshake.
    if (this.credential && this.authenticatedGeneration !== this.generation) {
      await this.authenticate(this.generation);
    }
    this.generation++; this.abort.abort(); this.abort = new AbortController();
    const generation = this.generation;
    const data = await this.request('/api/ui/bootstrap?tab=' + encodeURIComponent(this.tab), undefined, generation);
    await this.effectTail; this.requireCurrent(generation);
    this.snapshotGenerations.set(data, generation);
    this.seq = data.seq; this.connected = true;
    if (this.validCredential(data.reconnect)) {
      this.credential = Object.freeze({version: 1, installationId: data.reconnect.installationId, key: data.reconnect.key});
      try { sessionStorage.setItem('cashprediction.reconnect', JSON.stringify(this.credential)); }
      catch { /* Секрет остаётся в памяти даже без storage. */ }
    }
    return data;
  }

  /** Последовательно применяет эффекты с проверкой поколения после каждого await. */
  receive(data, generation = this.generation) {
    const work = this.effectTail.then(/** Применяет пакет после предыдущих эффектов, пропуская устаревшее поколение. */ async () => {
      if (!this.current(generation)) return;
      if (data.resync) { queueMicrotask(/** Запрашивает полный снимок после серверного указания пересинхронизации. */ () => this.resync().catch(/** Передаёт ошибку восстановления в обработчик потери связи. */ error => this.lost(error))); return; }
      for (const effect of data.effects || []) {
        if (!this.current(generation)) return;
        if (effect.seq <= this.seq) continue;
        if (effect.seq !== this.seq + 1) { queueMicrotask(/** Запрашивает полный снимок при разрыве последовательности эффектов. */ () => this.resync().catch(/** Передаёт ошибку восстановления после разрыва журнала в обработчик потери связи. */ error => this.lost(error))); return; }
        await this.apply(effect, generation);
        if (!this.current(generation)) return;
        this.seq = effect.seq;
      }
    });
    this.effectTail = work.catch(/** Обрабатывает сбой применения эффектов, игнорируя отмену устаревшего продолжения. */ error => { if (error.name !== 'AbortError') this.lost(error, generation); });
    return work;
  }

  /** Фиксирует поколение при постановке в очередь: неопределённые действия не повторяются. */
  intent(intent) {
    const generation = this.generation; const admitted = this.connected && !this.stopped; this.pending++;
    const work = this.tail.then(/** Отправляет принятое намерение после предыдущего, проверяя связь и исходное поколение. */ async () => {
      if (!admitted || !this.connected) throw cancelled();
      this.requireCurrent(generation);
      const result = await this.request('/api/ui/intent', {tab: this.tab, afterSeq: this.seq, intent}, generation);
      await this.receive(result, generation);
    });
    this.tail = work.catch(/** Регистрирует сбой намерения без повторной отправки и без обработки штатной отмены. */ error => { if (error.name !== 'AbortError') this.lost(error, generation); }).finally(/** Уменьшает число ожидающих запросов при любом исходе намерения. */ () => this.pending--);
    return work;
  }

  /** Отменяет старую модель до её передачи отрисовщику. */
  async query(query) {
    const generation = this.generation; if (!this.connected) throw cancelled(); this.pending++;
    try { return await this.request('/api/ui/query', query, generation); }
    finally { this.pending--; }
  }

  /** Составляет сообщение без завершающего перевода строки. */
  proofMessage(role, challenge) {
    return ['cashprediction-web-reconnect-v1', role, location.origin, challenge.installationId,
      challenge.serverGeneration, challenge.clientNonce, challenge.serverNonce, challenge.challengeId].join('\n');
  }
  /** Вычисляет HMAC в WebCrypto, не передавая исходный ключ. */
  async proof(key, role, challenge) {
    const bytes = Uint8Array.from(key.match(/../g), /** Преобразует пару шестнадцатеричных цифр ключа в байт. */ part => parseInt(part, 16));
    const imported = await crypto.subtle.importKey('raw', bytes, {name: 'HMAC', hash: 'SHA-256'}, false, ['sign']);
    return toHex(new Uint8Array(await crypto.subtle.sign('HMAC', imported, new TextEncoder().encode(this.proofMessage(role, challenge)))));
  }

  /** Отправляет только публичный challenge или clientProof без токена и redirects. */
  async reconnectRequest(path, body, generation) {
    this.requireCurrent(generation);
    const response = await fetch('/api/ui/reconnect/' + path, {
      method: 'POST', cache: 'no-store', redirect: 'error', credentials: 'omit',
      signal: AbortSignal.any([this.abort.signal, AbortSignal.timeout(5000)]),
      headers: {'X-CP-Reconnect': '1', 'Content-Type': 'application/json'}, body: JSON.stringify(body)
    });
    this.requireCurrent(generation);
    if (!response.ok) throw new Error('Reconnect HTTP ' + response.status);
    const data = await response.json(); this.requireCurrent(generation); return data;
  }

  /** Проверяет serverProof до отправки clientProof на тот же origin. */
  async authenticate(generation) {
    const credential = this.credential; if (!credential) return;
    const clientNonce = toHex(crypto.getRandomValues(new Uint8Array(32)));
    const challenge = await this.reconnectRequest('challenge', {installationId: credential.installationId, clientNonce}, generation);
    if (challenge.version !== 1 || challenge.installationId !== credential.installationId || challenge.clientNonce !== clientNonce
        || !hex(challenge.serverGeneration, 32) || !hex(challenge.serverNonce, 64) || !hex(challenge.challengeId, 32)
        || !hex(challenge.serverProof, 64)) throw new Error('Invalid reconnect challenge');
    const expected = await this.proof(credential.key, 'server', challenge); this.requireCurrent(generation);
    let difference = 0;
    for (let index = 0; index < expected.length; index++) difference |= expected.charCodeAt(index) ^ challenge.serverProof.charCodeAt(index);
    if (difference !== 0) throw new Error('Invalid reconnect server proof');
    const clientProof = await this.proof(credential.key, 'client', challenge); this.requireCurrent(generation);
    const result = await this.reconnectRequest('complete', {challengeId: challenge.challengeId, clientProof}, generation);
    if (result.serverGeneration !== challenge.serverGeneration || typeof result.token !== 'string'
        || !result.token || result.token.length > 512 || !/^[A-Za-z0-9_-]+$/.test(result.token)) throw new Error('Invalid reconnect completion');
    this.token = result.token; this.serverGeneration = result.serverGeneration;
    this.authenticatedGeneration = generation;
    try { sessionStorage.setItem('cashprediction.token', this.token); } catch { /* Токен остаётся в памяти. */ }
  }

  /** Объединяет инициаторов в один handshake и ждёт resync перед events. */
  recover() {
    if (this.stopped) return Promise.resolve();
    if (this.recovering) return this.recovering;
    this.recovering = (/** Повторяет доказательство сервера и восстановление снимка с ограниченной растущей задержкой. */ async () => {
      let delay = 250;
      while (!this.stopped && !this.connected) {
        const generation = this.generation;
        try {
          await this.authenticate(generation); this.requireCurrent(generation);
          await this.resync();
          if (this.stopped) return;
          if (this.connected) return;
        } catch {
          if (this.stopped) return;
          this.disconnected(new Error('Reconnect unavailable'));
        }
        await new Promise(/** Разрешает следующую попытку после текущей задержки восстановления. */ resolve => setTimeout(resolve, delay)); delay = Math.min(delay * 2, 4000);
      }
    })().finally(/** Освобождает общую задачу восстановления после завершения цикла. */ () => { this.recovering = null; });
    return this.recovering;
  }

  /** Читает журнал только после успешного восстановления полного состояния. */
  poll() {
    if (this.polling) return this.polling;
    this.polling = (/** Читает и применяет журнал живого поколения, ожидая восстановления при потере связи. */ async () => {
      while (!this.stopped) {
        if (!this.connected) { await this.recover(); continue; }
        const generation = this.generation;
        try {
          const data = await this.request('/api/ui/events?tab=' + encodeURIComponent(this.tab) + '&after=' + this.seq, undefined, generation);
          await this.receive(data, generation);
        } catch (error) {
          if (this.stopped) return;
          if (this.current(generation)) this.lost(error, generation);
        }
      }
    })();
    return this.polling;
  }

  /** Окончательно прекращает сеть при STOPPED/CRASHED или закрытии страницы. */
  stop() { this.stopped = true; this.generation++; this.abort.abort(); }
}
