/** @file Последовательный транспорт протокола ядра, без повторного исполнения намерений. */

/** Хранит номер применённого эффекта и очередь действий одной вкладки. */
export class Transport {
  /** Сохраняет обработчики эффектов и восстановления связи. */
  constructor(apply, disconnected, resync) {
    this.tab = crypto.randomUUID();
    this.seq = 0;
    this.apply = apply;
    this.disconnected = disconnected;
    this.resync = resync;
    this.tail = Promise.resolve();
    this.effectTail = Promise.resolve();
    this.pending = 0;
    this.stopped = false;
    this.generation = 0;
    this.abort = new AbortController();
    const params = new URLSearchParams(location.search);
    this.token = params.get('t') || '';
    try {
      this.token ||= sessionStorage.getItem('cashprediction.token') || '';
      if (this.token) sessionStorage.setItem('cashprediction.token', this.token);
      if (params.has('t')) {
        params.delete('t');
        history.replaceState(null, '', location.pathname + (params.size ? `?${params}` : '') + location.hash);
      }
    } catch { /* Закрытый storage не мешает текущей вкладке. */ }
  }

  /** Выполняет один запрос с ключом доступа сервера. */
  async request(path, body, signal) {
    const response = await fetch(path, {
      method: body === undefined ? 'GET' : 'POST', cache: 'no-store', signal,
      headers: {'X-Token': this.token, ...(body === undefined ? {} : {'Content-Type': 'application/json'})},
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const data = await response.json();
    if (response.status === 503 && data.busy) return {effects: []};
    if (!response.ok) throw new Error(data.error || String(response.status));
    return data;
  }

  /** Получает целостный снимок после старта или потери журнала. */
  async bootstrap() {
    this.generation++;
    const data = await this.request(`/api/ui/bootstrap?tab=${encodeURIComponent(this.tab)}`);
    await this.effectTail;
    this.seq = data.seq;
    return data;
  }

  /** Последовательно применяет эффекты из параллельных ответов. */
  receive(data, generation = this.generation) {
    const work = this.effectTail.then(async () => {
      if (generation !== this.generation) return;
      if (data.resync) { queueMicrotask(() => this.resync()); return; }
      for (const effect of data.effects || []) {
        if (effect.seq <= this.seq) continue;
        if (effect.seq !== this.seq + 1) { queueMicrotask(() => this.resync()); return; }
        await this.apply(effect);
        this.seq = effect.seq;
      }
    });
    this.effectTail = work.catch(error => this.disconnected(error));
    return work;
  }

  /** Посылает намерение ровно один раз, сохраняя порядок ввода. */
  intent(intent) {
    this.pending++;
    const work = this.tail.then(async () => {
      const generation = this.generation;
      const result = await this.request('/api/ui/intent', {tab: this.tab, afterSeq: this.seq, intent});
      await this.receive(result, generation);
    });
    this.tail = work.catch(error => this.disconnected(error)).finally(() => this.pending--);
    return work;
  }

  /** Запрашивает готовую модель без изменения состояния приложения. */
  async query(query) {
    this.pending++;
    try { return await this.request('/api/ui/query', query); }
    finally { this.pending--; }
  }

  /** Читает журнал до остановки страницы; ошибки сети передаёт экрану связи. */
  async poll() {
    while (!this.stopped) {
      try {
        const generation = this.generation;
        const data = await this.request(`/api/ui/events?tab=${encodeURIComponent(this.tab)}&after=${this.seq}`, undefined, this.abort.signal);
        await this.receive(data, generation);
      } catch (error) {
        if (this.stopped) return;
        this.disconnected(error);
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
    }
  }

  /** Отменяет только ожидающее чтение при завершении приложения. */
  stop() { this.stopped = true; this.abort.abort(); }
}
