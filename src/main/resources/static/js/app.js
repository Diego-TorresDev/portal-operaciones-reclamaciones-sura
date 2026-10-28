/* Frontend estático para el Portal de Reclamaciones. Sin frameworks ni dependencias de compilación. */
(() => {
  'use strict';

  const API_BASE = '';
  const MIN_OBSERVATION = 20;
  const MIN_REASON = 10;
  const state = {
    token: sessionStorage.getItem('sura_jwt') || '',
    username: sessionStorage.getItem('sura_username') || '',
    role: sessionStorage.getItem('sura_role') || '',
    page: 0,
    totalPages: 1,
    totalCases: 0,
    currentCases: [],
    pendingFile: null,
    latestBatch: null,
    currentDetailCode: null,
    caseLoadId: 0
  };

  const $ = (id) => document.getElementById(id);
  const loginScreen = $('loginScreen');
  const appShell = $('appShell');

  const ALIASES = {
    total: ['totalCasos', 'total', 'cantidadTotal', 'totalRegistros', 'casosTotales'],
    automatic: ['automaticos', 'casosAutomaticos', 'totalAutomaticos', 'cantidadAutomaticos', 'aprobadosAutomaticos'],
    manual: ['manuales', 'casosManuales', 'totalManuales', 'cantidadManuales'],
    automation: ['porcentajeAutomatizacion', 'porcentajeAutomaticos', 'tasaAutomatizacion', 'porcentajeAutomatizacionPct'],
    toPay: ['valorAPagar', 'valorPendientePago', 'totalValorAPagar', 'montoAPagar', 'valorAprobado'],
    value: ['valorTotal', 'montoTotal', 'valor', 'totalValor'],
    state: ['porEstado', 'casosPorEstado', 'estados', 'distribucionEstados', 'conteoPorEstado'],
    priority: ['porPrioridad', 'casosPorPrioridad', 'prioridades', 'distribucionPrioridad', 'conteoPorPrioridad'],
    semaphore: ['porSemaforo', 'casosPorSemaforo', 'semaforos', 'distribucionSemaforo', 'conteoPorSemaforo']
  };

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (char) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[char]);
  }

  function normalizeKey(value) {
    return String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]/g, '');
  }

  function getValue(obj, candidates, fallback = undefined) {
    if (!obj || typeof obj !== 'object') return fallback;
    const properties = Object.keys(obj);
    for (const candidate of candidates) {
      const found = properties.find((key) => normalizeKey(key) === normalizeKey(candidate));
      if (found !== undefined && obj[found] !== null && obj[found] !== undefined) return obj[found];
    }
    return fallback;
  }

  function text(value, fallback = '—') {
    if (value === null || value === undefined || value === '') return fallback;
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  }

  function currency(value) {
    const numeric = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[^0-9,.-]/g, '').replace(/\.(?=\d{3}(?:\D|$))/g, '').replace(',', '.'));
    if (!Number.isFinite(numeric)) return text(value);
    return new Intl.NumberFormat('es-CO', { style: 'currency', currency: 'COP', maximumFractionDigits: 0 }).format(numeric);
  }

  function prettyLabel(value) {
    return text(value).replace(/_/g, ' ').toLowerCase().replace(/(^|\s)\S/g, (m) => m.toUpperCase());
  }

  function decodeJwtPayload(token) {
    try {
      const segment = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      const padded = segment + '='.repeat((4 - segment.length % 4) % 4);
      return JSON.parse(decodeURIComponent(Array.from(atob(padded), (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join('')));
    } catch (_) { return {}; }
  }

  function deriveRole(loginResult, token, username) {
    const principal = getValue(loginResult, ['user', 'usuario', 'perfil'], {}) || {};
    const fromResponse = getValue(loginResult, ['role', 'rol', 'authority', 'authorities'], getValue(principal, ['role', 'rol', 'authority', 'authorities'], ''));
    const claims = decodeJwtPayload(token);
    const claim = getValue(claims, ['scope', 'scp', 'roles', 'authorities'], '');
    const combined = `${Array.isArray(fromResponse) ? fromResponse.join(' ') : fromResponse} ${Array.isArray(claim) ? claim.join(' ') : claim} ${username}`.toUpperCase();
    if (combined.includes('ADMIN')) return 'ADMIN';
    if (combined.includes('ANALISTA')) return 'ANALISTA';
    return 'USUARIO';
  }

  function showToast(message, kind = '') {
    const toast = document.createElement('div');
    toast.className = `toast ${kind}`;
    toast.textContent = message;
    $('toastRegion').appendChild(toast);
    window.setTimeout(() => toast.remove(), 5500);
  }

  function setButtonBusy(button, busy, busyLabel = 'Procesando…') {
    if (!button) return;
    if (busy) {
      button.dataset.originalText = button.textContent;
      button.textContent = busyLabel;
      button.disabled = true;
    } else {
      button.textContent = button.dataset.originalText || button.textContent;
      button.disabled = false;
    }
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (state.token) headers.set('Authorization', `Bearer ${state.token}`);
    const isFormData = options.body instanceof FormData;
    if (options.body !== undefined && !isFormData && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    const response = await fetch(`${API_BASE}${path}`, { ...options, headers, credentials: 'same-origin' });
    if (response.status === 401 && state.token) {
      clearSession();
      throw new Error('Tu sesión expiró. Inicia sesión nuevamente.');
    }
    if (!response.ok) {
      let message = `La solicitud falló (HTTP ${response.status}).`;
      const contentType = response.headers.get('content-type') || '';
      try {
        if (contentType.includes('json')) {
          const body = await response.json();
          message = text(getValue(body, ['message', 'mensaje', 'error', 'detail', 'descripcion']), message);
        } else {
          const bodyText = await response.text();
          if (bodyText && bodyText.length < 600) message = bodyText;
        }
      } catch (_) { /* Dejar mensaje genérico. */ }
      throw new Error(message);
    }
    if (response.status === 204) return null;
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json') || contentType.includes('+json')) return response.json();
    return response;
  }

  function clearSession() {
    state.token = '';
    state.username = '';
    state.role = '';
    sessionStorage.removeItem('sura_jwt');
    sessionStorage.removeItem('sura_username');
    sessionStorage.removeItem('sura_role');
    loginScreen.classList.remove('hidden');
    appShell.classList.add('hidden');
    $('password').value = '';
    $('loginError').textContent = '';
  }

  function applyRoleVisibility() {
    document.querySelectorAll('.admin-only').forEach((el) => {
      el.classList.toggle('hidden', state.role !== 'ADMIN');
    });
    document.querySelectorAll('.admin-analyst').forEach((el) => {
      el.classList.toggle('hidden', !['ADMIN', 'ANALISTA'].includes(state.role));
    });
    $('currentUsername').textContent = state.username || 'Usuario';
    $('currentRole').textContent = state.role || 'USUARIO';
    $('userAvatar').textContent = (state.username || 'U').slice(0, 1).toUpperCase();
    if (state.role === 'ADMIN') document.querySelector('[data-view="parameters"]').classList.remove('hidden');
  }

  function enterApplication() {
    loginScreen.classList.add('hidden');
    appShell.classList.remove('hidden');
    applyRoleVisibility();
    showView('dashboard');
    loadDashboard();
  }

  function showView(view) {
    const allowed = ['dashboard', 'cases', 'import', 'batches', 'parameters'];
    if (!allowed.includes(view)) return;
    if (view === 'parameters' && state.role !== 'ADMIN') {
      showToast('Solo ADMIN puede consultar los parámetros.', 'error');
      return;
    }
    document.querySelectorAll('.view-section').forEach((section) => section.classList.add('hidden'));
    $(`view-${view}`).classList.remove('hidden');
    document.querySelectorAll('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.view === view));
    const titles = { dashboard: 'Tablero', cases: 'Gestión de casos', import: 'Carga de archivo', batches: 'Lotes de pago', parameters: 'Parámetros' };
    $('pageTitle').textContent = titles[view];
    $('sidebar').classList.remove('open');
    if (view === 'cases') loadCases();
    if (view === 'parameters') loadParameters();
  }

  function firstArray(value, depth = 0) {
    if (Array.isArray(value)) return value;
    if (!value || typeof value !== 'object' || depth > 5) return [];

    // Spring Page/DTO can wrap the list at different levels. Prefer the
    // known list properties and search nested response wrappers safely.
    const keys = [
      'contenido', 'content', 'casos', 'items', 'data', 'resultados',
      'results', 'records', 'elements', 'parameters', 'parametros',
      'resultado', 'result', 'payload', 'page', 'lista', 'list'
    ];
    for (const key of keys) {
      const candidate = getValue(value, [key]);
      if (Array.isArray(candidate)) return candidate;
      if (candidate && typeof candidate === 'object') {
        const nested = firstArray(candidate, depth + 1);
        if (nested.length || Array.isArray(candidate)) return nested;
      }
    }
    return [];
  }

  function findNestedNumber(value, candidates, depth = 0) {
    if (!value || typeof value !== 'object' || depth > 5) return null;
    const direct = getValue(value, candidates);
    const number = countNumber(direct);
    if (number !== null) return number;
    for (const key of ['contenido', 'content', 'data', 'resultado', 'result', 'payload', 'page']) {
      const nested = getValue(value, [key]);
      if (nested && typeof nested === 'object') {
        const found = findNestedNumber(nested, candidates, depth + 1);
        if (found !== null) return found;
      }
    }
    return null;
  }

  function countNumber(value) {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'string') {
      const parsed = Number(value.replace(/[^0-9.-]/g, ''));
      if (Number.isFinite(parsed)) return parsed;
    }
    return null;
  }

  function findCounts(data, candidates) {
    const found = getValue(data, candidates);
    if (found === undefined) return null;
    if (Array.isArray(found)) return found.length;
    if (typeof found === 'object') {
      if (Array.isArray(getValue(found, ['content', 'items', 'casos']))) return firstArray(found).length;
      return found;
    }
    return countNumber(found);
  }

  function countRows(value) {
    if (Array.isArray(value)) return value.map((item) => ({
      label: text(getValue(item, ['estado', 'prioridad', 'semaforo', 'nombre', 'clave', 'categoria', 'label'], 'Sin categoría')),
      count: countNumber(getValue(item, ['cantidad', 'total', 'conteo', 'casos', 'count', 'valor'], 0)) ?? 0
    }));
    if (value && typeof value === 'object') {
      return Object.entries(value).map(([label, number]) => ({ label, count: countNumber(typeof number === 'object' ? getValue(number, ['cantidad', 'total', 'count', 'valor'], 0) : number) ?? 0 }));
    }
    return [];
  }

  function candidateNested(data, candidates) {
    const direct = getValue(data, candidates);
    if (direct !== undefined) return direct;
    for (const key of ['distribuciones', 'distribucion', 'estadisticas', 'desglose']) {
      const nested = getValue(data, [key]);
      const result = getValue(nested, candidates);
      if (result !== undefined) return result;
    }
    return undefined;
  }

  function renderMetric(label, value, foot, icon) {
    return `<article class="metric-card"><span class="metric-label">${escapeHtml(label)}</span><strong class="metric-value">${escapeHtml(value)}</strong><span class="metric-foot">${escapeHtml(foot || icon || '')}</span></article>`;
  }

  function renderDistribution(containerId, rows, kind = '') {
    const container = $(containerId);
    if (!rows.length) {
      container.innerHTML = '<p class="muted">La respuesta no incluye datos de esta distribución.</p>';
      return;
    }
    const max = Math.max(1, ...rows.map((row) => row.count));
    if (kind === 'states') {
      container.innerHTML = rows.sort((a, b) => b.count - a.count).map((row) => `<div class="distribution-row"><span class="distribution-label">${escapeHtml(prettyLabel(row.label))}</span><div class="progress-track"><div class="progress-fill" style="width:${Math.max(0, Math.min(100, row.count / max * 100))}%"></div></div><strong class="distribution-count">${row.count}</strong></div>`).join('');
    } else if (kind === 'priority') {
      container.innerHTML = rows.sort((a, b) => b.count - a.count).map((row) => `<div class="priority-item"><span class="priority-left"><span class="priority-dot ${escapeHtml(row.label.toLowerCase())}"></span>${escapeHtml(prettyLabel(row.label))}</span><strong class="priority-count">${row.count}</strong></div>`).join('');
    } else {
      container.innerHTML = rows.sort((a, b) => b.count - a.count).map((row) => `<div class="semaphore-item"><span class="semaphore-left"><span class="semaphore-dot ${escapeHtml(row.label.toUpperCase())}"></span>${escapeHtml(prettyLabel(row.label))}</span><strong>${row.count}</strong></div>`).join('');
    }
  }

  async function loadDashboard() {
    $('dashboardAlert').classList.add('hidden');
    try {
      const data = await api('/api/reportes/resumen');
      const total = findCounts(data, ['totalCasos', 'total', 'cantidadTotal', 'totalRegistros', 'casosTotales']);
      const automatic = findCounts(data, ['automaticos', 'casosAutomaticos', 'totalAutomaticos', 'cantidadAutomaticos', 'aprobadosAutomaticos']);
      const manual = findCounts(data, ['manuales', 'casosManuales', 'totalManuales', 'cantidadManuales']);
      let automation = findCounts(data, ['porcentajeAutomatizacion', 'porcentajeAutomaticos', 'tasaAutomatizacion']);
      if (automation === null && automatic !== null && total) automation = Math.round(automatic / total * 1000) / 10;
      const toPay = findCounts(data, ['valorAPagar', 'valorPendientePago', 'totalValorAPagar', 'montoAPagar', 'valorAprobado']);
      $('metricsGrid').innerHTML = [
        renderMetric('Total de casos', total === null ? '—' : total, 'Casos registrados'),
        renderMetric('Automáticos', automatic === null ? '—' : automatic, total !== null && total ? `${Math.round(automatic / total * 100)}% del total` : 'Decisión automática'),
        renderMetric('Manuales', manual === null ? '—' : manual, 'Requieren o requirieron revisión'),
        renderMetric('Automatización', automation === null ? '—' : `${automation}%`, toPay === null ? 'Porcentaje de automatización' : `Valor a pagar: ${currency(toPay)}`)
      ].join('');
      const states = countRows(candidateNested(data, ALIASES.state));
      const priorities = countRows(candidateNested(data, ALIASES.priority));
      const semaphores = countRows(candidateNested(data, ALIASES.semaphore));
      renderDistribution('stateDistribution', states, 'states');
      renderDistribution('priorityDistribution', priorities, 'priority');
      renderDistribution('semaphoreDistribution', semaphores, 'semaphore');
      if (!states.length || !priorities.length || !semaphores.length) {
        $('dashboardAlert').textContent = 'El resumen se recibió correctamente. Algunas distribuciones no se pudieron mapear automáticamente; puedes verificar sus nombres en la respuesta de /api/reportes/resumen.';
        $('dashboardAlert').classList.remove('hidden');
      }
    } catch (error) {
      $('dashboardAlert').textContent = `No se pudo cargar el resumen: ${error.message}`;
      $('dashboardAlert').classList.remove('hidden');
      $('metricsGrid').innerHTML = ['Total de casos', 'Automáticos', 'Manuales', 'Automatización'].map((label) => renderMetric(label, '—', 'No disponible')).join('');
    }
  }

  function caseCode(item) { return text(getValue(item, ['codigoCaso', 'identificador', 'idCaso', 'codigo', 'caseId', 'id'], '')); }
  function caseState(item) { return text(getValue(item, ['estadoActual', 'estado', 'status'], '')); }
  function caseDecision(item) { return text(getValue(item, ['decision', 'tipoDecision', 'clasificacion'], '')); }
  function casePriority(item) { return text(getValue(item, ['prioridad'], 'SIN_PRIORIDAD')); }
  function caseSemaphore(item) { return text(getValue(item, ['semaforoANS', 'semaforo', 'semaforoAns'], '')); }
  function caseValue(item) { return getValue(item, ['valorReclamado', 'valor', 'monto', 'valorCaso', 'importe'], ''); }
  function caseName(item) { return text(getValue(item, ['nombreAfiliado', 'afiliado', 'nombre', 'beneficiario'], 'Afiliado')); }
  function caseRules(item) {
    const raw = getValue(item, ['reglasIncumplidas', 'reglas', 'reglasFallidas', 'reglasNoCumplidas'], []);
    if (Array.isArray(raw)) return raw.map((entry) => typeof entry === 'object' ? text(getValue(entry, ['codigoRegla', 'codigo', 'regla', 'id'], JSON.stringify(entry))) : String(entry)).join(', ');
    return text(raw, '—');
  }

  function getListResponse(data) {
    const rows = firstArray(data);
    const total = findNestedNumber(data, [
      'totalElementos', 'totalElements', 'totalRegistros', 'total',
      'cantidadTotal', 'totalCasos', 'totalCount', 'count'
    ]) ?? rows.length;
    // Prioriza el tamaño realmente reportado por la API. Algunos servidores
    // pueden limitar el tamano solicitado y devolver un valor efectivo menor.
    const pageSize = Math.max(1, Number(
      getValue(data, ['tamano', 'size', 'pageSize'], $('pageSize')?.value || 20)
    ) || 20);
    const pages = findNestedNumber(data, ['totalPaginas', 'totalPages', 'paginas', 'pages'])
      ?? Math.max(1, Math.ceil(total / pageSize));
    return { rows, total, pages, pageSize };
  }

  function caseRow(item) {
    const code = caseCode(item);
    const decision = caseDecision(item).toUpperCase();
    const priority = casePriority(item).toUpperCase();
    const semaphore = caseSemaphore(item).toUpperCase();
    return `<tr>
      <td><div class="case-cell"><strong>${escapeHtml(code || 'Sin identificador')}</strong><small>${escapeHtml(caseName(item))}</small><small>${escapeHtml(getValue(item, ['documentoEnmascarado', 'documentoMask', 'documentoAfiliado'], ''))}</small></div></td>
      <td><span class="decision-tag ${decision.includes('AUTO') ? 'automatic' : 'manual'}">${escapeHtml(prettyLabel(decision || '—'))}</span></td>
      <td><strong>${escapeHtml(currency(caseValue(item)))}</strong></td>
      <td><span class="priority-tag ${escapeHtml(priority)}">${escapeHtml(prettyLabel(priority))}</span></td>
      <td><span class="state-tag">${escapeHtml(prettyLabel(caseState(item)))}</span></td>
      <td><span class="semaphore-tag ${escapeHtml(semaphore)}">${escapeHtml(prettyLabel(semaphore))}</span></td>
      <td><button class="table-action" data-case-detail="${escapeHtml(code)}">Ver detalle →</button></td>
    </tr>`;
  }

  function normalizeSearch(value) {
    return String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('es-CO').trim();
  }

  async function loadCases() {
    const loadId = (state.caseLoadId || 0) + 1;
    state.caseLoadId = loadId;

    const searchText = $('caseSearch').value.trim();
    const search = normalizeSearch(searchText);
    const requestedSize = Number($('pageSize').value || 20);
    const filters = {
      estado: $('filterEstado').value,
      decision: $('filterDecision').value,
      prioridad: $('filterPrioridad').value,
      regla: $('filterRegla').value.trim(),
      semaforo: $('filterSemaforo').value
    };

    const makeParams = (page, size) => {
      const params = new URLSearchParams();
      Object.entries({ ...filters, pagina: page, tamano: size }).forEach(([key, value]) => {
        if (value !== '' && value !== null && value !== undefined) params.set(key, value);
      });
      return params.toString();
    };

    $('casesTableBody').innerHTML = '<tr><td colspan="7" class="table-empty">Cargando casos…</td></tr>';
    $('caseCount').textContent = search ? 'Buscando en todos los resultados…' : 'Consultando casos…';

    try {
      // Sin texto de búsqueda, dejamos que el backend haga la paginación normal.
      if (!search) {
        const data = await api(`/api/casos?${makeParams(state.page, requestedSize)}`);
        if (loadId !== state.caseLoadId) return;

        const response = getListResponse(data);
        const rows = response.rows;
        const total = response.total;
        const pages = Math.max(1, response.pages);

        // Si la página actual dejó de existir tras aplicar un filtro, volvemos
        // a la última página válida en vez de mostrar un listado vacío engañoso.
        if (state.page >= pages && state.page > 0) {
          state.page = pages - 1;
          return loadCases();
        }

        state.currentCases = rows;
        state.totalCases = total;
        state.totalPages = pages;
        $('caseCount').textContent = `${total} caso(s) en el resultado`;
        $('pageInfo').textContent = `Página ${state.page + 1} de ${pages}`;
        $('previousPage').disabled = state.page <= 0;
        $('nextPage').disabled = state.page + 1 >= pages;

        if (rows.length) {
          $('casesTableBody').innerHTML = rows.map(caseRow).join('');
        } else if (total === 0) {
          $('casesTableBody').innerHTML = '<tr><td colspan="7" class="table-empty">No hay casos que coincidan con los filtros.</td></tr>';
        } else {
          $('casesTableBody').innerHTML = '<tr><td colspan="7" class="table-empty">La API informó resultados, pero no se pudo interpretar la lista. Revisa la respuesta de /api/casos.</td></tr>';
        }
        return;
      }

      // No existe un parámetro de búsqueda textual en el backend. Para que el
      // buscador funcione en TODAS las páginas, recopilamos todas las páginas
      // del conjunto que ya está filtrado en el servidor y luego buscamos en
      // sus campos. No dependemos de que tamano=1000 sea aceptado: usamos la
      // metadata real de la API y solicitamos las páginas restantes si hace falta.
      // El backend valida el tamaño máximo de página. Usamos el tamaño elegido
      // en el selector (10, 20 o 50), no 1000, para evitar HTTP 400.
      const searchFetchSize = Math.min(50, Math.max(1, requestedSize || 20));
      const firstData = await api(`/api/casos?${makeParams(0, searchFetchSize)}`);
      if (loadId !== state.caseLoadId) return;
      const firstPage = getListResponse(firstData);
      const allRows = [...firstPage.rows];
      const totalServer = Math.max(firstPage.total, allRows.length);
      const metadataPageSize = Math.max(1, firstPage.pageSize || searchFetchSize);

      // Si el backend limita silenciosamente tamano, la cantidad real de filas
      // recibidas en la primera página sirve como respaldo para no saltarnos
      // registros al consultar pagina=1, pagina=2, etc.
      const effectivePageSize = totalServer > allRows.length && allRows.length > 0
        ? Math.min(metadataPageSize, allRows.length)
        : metadataPageSize;
      const pagesServer = allRows.length >= totalServer
        ? 1
        : Math.max(
            1,
            firstPage.pages || 1,
            Math.ceil(totalServer / effectivePageSize)
          );

      // Recuperar páginas por lotes pequeños para no disparar demasiadas
      // solicitudes concurrentes si el conjunto de datos crece.
      const batchSize = 4;
      for (let page = 1; page < pagesServer; page += batchSize) {
        const pageNumbers = [];
        for (let next = page; next < Math.min(page + batchSize, pagesServer); next++) {
          pageNumbers.push(next);
        }
        const pageResponses = await Promise.all(pageNumbers.map(async (pageNumber) => {
          // Usamos el tamaño efectivo de página, no el tamaño solicitado,
          // por si el servidor ha aplicado un límite máximo.
          const pageData = await api(`/api/casos?${makeParams(pageNumber, effectivePageSize)}`);
          return getListResponse(pageData).rows;
        }));
        pageResponses.forEach((pageRows) => allRows.push(...pageRows));
        if (loadId !== state.caseLoadId) return;
      }

      // Deduplicamos por código de caso por seguridad, conservando el primer
      // registro encontrado si el servidor repite una fila entre páginas.
      const uniqueRows = [];
      const seenCodes = new Set();
      for (const item of allRows) {
        const code = caseCode(item) || String(getValue(item, ['id'], ''));
        if (!code || !seenCodes.has(code)) {
          uniqueRows.push(item);
          if (code) seenCodes.add(code);
        }
      }

      const matching = uniqueRows.filter((item) => {
        const searchable = [
          caseCode(item),
          caseName(item),
          getValue(item, ['documentoEnmascarado', 'documentoMask'], ''),
          caseDecision(item),
          caseState(item),
          casePriority(item),
          caseSemaphore(item),
          caseRules(item),
          getValue(item, ['motivo', 'motivoNegocio', 'observaciones'], ''),
          caseValue(item)
        ].map(normalizeSearch).join(' ');
        return searchable.includes(search);
      });

      const total = matching.length;
      const pages = Math.max(1, Math.ceil(total / requestedSize));
      if (state.page >= pages) state.page = pages - 1;
      const start = state.page * requestedSize;
      const rows = matching.slice(start, start + requestedSize);

      if (loadId !== state.caseLoadId) return;
      state.currentCases = rows;
      state.totalCases = total;
      state.totalPages = pages;
      $('caseCount').textContent = `${total} caso(s) en el resultado · búsqueda global: “${escapeHtml(searchText)}”`;
      $('pageInfo').textContent = `Página ${state.page + 1} de ${pages}`;
      $('previousPage').disabled = state.page <= 0;
      $('nextPage').disabled = state.page + 1 >= pages;

      if (rows.length) {
        $('casesTableBody').innerHTML = rows.map(caseRow).join('');
      } else {
        $('casesTableBody').innerHTML = '<tr><td colspan="7" class="table-empty">No hay casos que coincidan con los filtros y la búsqueda.</td></tr>';
      }
    } catch (error) {
      if (loadId !== state.caseLoadId) return;
      $('caseCount').textContent = 'No fue posible consultar el listado';
      $('pageInfo').textContent = 'Página 1 de 1';
      $('casesTableBody').innerHTML = `<tr><td colspan="7" class="table-empty">${escapeHtml(error.message)}</td></tr>`;
    }
  }

  function detailFields(detail) {
    const fields = [
      ['Identificador', ['codigoCaso', 'identificador', 'idCaso', 'codigo', 'id']],
      ['Estado actual', ['estadoActual', 'estado']],
      ['Decisión', ['decision']],
      ['Afiliado', ['nombreAfiliado', 'afiliado', 'nombre']],
      ['Documento', ['documento', 'numeroDocumento']],
      ['Documento enmascarado', ['documentoEnmascarado']],
      ['Póliza', ['poliza', 'numeroPoliza']],
      ['Valor reclamado', ['valorReclamado', 'valor', 'monto']],
      ['Prioridad', ['prioridad']],
      ['Fecha de radicación', ['fechaRadicacion', 'fechaRadicado']],
      ['Fecha límite ANS', ['fechaLimiteANS', 'fechaLimiteAns', 'fechaLimite']],
      ['Semáforo ANS', ['semaforoANS', 'semaforo']],
      ['Analista asignado', ['analistaAsignado', 'usuarioAsignado', 'asignadoA']],
      ['Motivo', ['motivo', 'motivoDecision']]
    ];
    return fields.filter(([, keys]) => getValue(detail, keys) !== undefined).map(([label, keys]) => {
      const value = getValue(detail, keys);
      const display = keys[0].toLowerCase().includes('valor') || keys.some((key) => ['valorReclamado', 'monto'].includes(key)) ? currency(value) : text(value);
      return `<div class="detail-field"><span>${escapeHtml(label)}</span><strong>${escapeHtml(display)}</strong></div>`;
    }).join('');
  }

  function renderRules(detail) {
    const rules = getValue(detail, ['detalleReglas', 'reglasDetalle', 'reglasEvaluadas', 'resultadosReglas', 'reglasIncumplidas', 'reglas'], []);
    if (!Array.isArray(rules) || !rules.length) return '<p class="muted">No se recibió el detalle de reglas en esta respuesta.</p>';
    return rules.map((rule) => {
      if (typeof rule !== 'object') return `<div class="rule-item"><strong>${escapeHtml(text(rule))}</strong></div>`;
      const code = getValue(rule, ['codigoRegla', 'codigo', 'id', 'regla'], 'Regla');
      const name = getValue(rule, ['nombreRegla', 'nombre', 'titulo', 'descripcion'], '');
      const reason = getValue(rule, ['motivo', 'detalle', 'mensaje', 'observacion'], '');
      const passed = getValue(rule, ['cumple', 'aprobada', 'resultado', 'estado'], undefined);
      const status = passed === true || String(passed).toUpperCase() === 'CUMPLE' || String(passed).toUpperCase() === 'APROBADA' ? 'Cumple' : (passed === false || /INCUMPLE|FALLA|RECHAZ/i.test(String(passed)) ? 'Incumple' : 'Resultado');
      return `<div class="rule-item"><strong>${escapeHtml(text(code))} · ${escapeHtml(text(name, ''))}</strong><p>${escapeHtml(text(reason, status))}</p></div>`;
    }).join('');
  }

  function renderTracking(tracking) {
    // El endpoint real devuelve un objeto contenedor con la lista bajo
    // la propiedad "historial", junto con idCaso, estadoActual y cantidadEventos.
    // También aceptamos arrays directos y formatos alternativos para mantener
    // compatibilidad con respuestas paginadas o DTOs futuros.
    const historial = getValue(tracking, ['historial', 'eventos', 'tracking', 'history'], undefined);
    const rows = Array.isArray(tracking)
      ? tracking
      : (Array.isArray(historial) ? historial : firstArray(tracking));
    if (!rows.length) return '<p class="muted">No se recibieron eventos de tracking.</p>';
    return rows.map((item) => {
      if (typeof item !== 'object') return `<div class="tracking-item"><div class="tracking-rail"><span></span></div><div class="tracking-content"><strong>${escapeHtml(text(item))}</strong></div></div>`;
      const status = getValue(item, ['estadoNuevo', 'estado', 'estadoActual', 'nuevoEstado'], 'Evento');
      const date = getValue(item, ['fechaHora', 'fechaCambio', 'timestamp', 'fecha', 'createdAt'], '');
      const user = getValue(item, ['usuario', 'usuarioCambio', 'actor'], '');
      const comment = getValue(item, ['comentario', 'observacion', 'mensaje'], '');
      return `<div class="tracking-item"><div class="tracking-rail"><span></span></div><div class="tracking-content"><strong>${escapeHtml(prettyLabel(status))}</strong><p>${escapeHtml(text(date, ''))}${user ? ` · ${escapeHtml(text(user))}` : ''}</p>${comment ? `<p>${escapeHtml(text(comment))}</p>` : ''}</div></div>`;
    }).join('');
  }

  function detailActionButtons(detail) {
    const code = caseCode(detail) || state.currentDetailCode;
    const currentState = caseState(detail).toUpperCase();
    let html = '';
    if (state.role === 'ANALISTA' && currentState === 'EN_BANDEJA') html += `<button class="button button-primary" data-take-case="${escapeHtml(code)}">Tomar caso</button>`;
    if (state.role === 'ANALISTA' && currentState === 'EN_REVISION') {
      html += `<button class="button button-success" data-decision="APROBAR" data-code="${escapeHtml(code)}">Aprobar</button>`;
      html += `<button class="button button-danger" data-decision="OBJETAR" data-code="${escapeHtml(code)}">Objetar</button>`;
    }
    return html ? `<div class="detail-actions">${html}</div>` : '';
  }

  async function openCaseDetail(code) {
    if (!code) return;
    state.currentDetailCode = code;
    $('caseModalTitle').textContent = code;
    $('caseModalSubtitle').textContent = 'Consultando datos y trazabilidad…';
    $('caseModalBody').innerHTML = '<p class="muted">Cargando detalle del caso y tracking…</p>';
    $('caseModal').classList.remove('hidden');
    try {
      const [detail, tracking] = await Promise.all([
        api(`/api/casos/${encodeURIComponent(code)}`),
        api(`/api/casos/${encodeURIComponent(code)}/tracking`).catch((error) => ({ _error: error.message }))
      ]);
      const realDetail = detail?.data || detail?.caso || detail;
      const trackError = tracking && tracking._error;
      $('caseModalSubtitle').textContent = `${prettyLabel(caseState(realDetail))} · ${prettyLabel(caseDecision(realDetail))}`;
      const motive = getValue(realDetail, ['motivo', 'motivoDecision', 'explicacion'], '');
      $('caseModalBody').innerHTML = `<div class="detail-grid">${detailFields(realDetail)}</div>
        <section class="detail-section"><h3>Reglas evaluadas</h3><div>${renderRules(realDetail)}</div></section>
        ${motive ? `<section class="detail-section"><h3>Motivo de negocio</h3><div class="rule-item"><p>${escapeHtml(text(motive))}</p></div></section>` : ''}
        <section class="detail-section"><h3>Historial de estados</h3>${trackError ? `<p class="muted">No se pudo cargar el tracking: ${escapeHtml(trackError)}</p>` : renderTracking(tracking)}</section>
        ${detailActionButtons(realDetail)}`;
    } catch (error) {
      $('caseModalBody').innerHTML = `<div class="notice note-warning">No se pudo cargar el detalle: ${escapeHtml(error.message)}</div>`;
    }
  }

  async function takeCase(code) {
    try {
      const result = await api(`/api/casos/${encodeURIComponent(code)}/tomar`, { method: 'POST' });
      showToast(`Caso ${code} tomado correctamente.`, 'success');
      await loadCases();
      await openCaseDetail(code);
    } catch (error) { showToast(error.message, 'error'); }
  }

  function openDecisionModal(code, action) {
    $('decisionCaseCode').value = code;
    $('decisionAction').value = action;
    $('decisionModalTitle').textContent = action === 'APROBAR' ? 'Aprobar reclamación' : 'Objetar reclamación';
    $('decisionModalSubtitle').textContent = `Caso ${code}`;
    $('decisionObservation').value = '';
    $('observationCount').textContent = '0';
    $('submitDecisionButton').textContent = action === 'APROBAR' ? 'Confirmar aprobación' : 'Confirmar objeción';
    $('decisionModal').classList.remove('hidden');
    $('decisionObservation').focus();
  }

  async function submitDecision(event) {
    event.preventDefault();
    const code = $('decisionCaseCode').value;
    const action = $('decisionAction').value;
    const observacion = $('decisionObservation').value.trim();
    if (observacion.length < MIN_OBSERVATION) {
      showToast(`La observación debe tener al menos ${MIN_OBSERVATION} caracteres.`, 'error');
      return;
    }
    const button = $('submitDecisionButton');
    setButtonBusy(button, true, 'Guardando…');
    try {
      await api(`/api/casos/${encodeURIComponent(code)}/estado`, {
        method: 'PATCH', body: JSON.stringify({ accion: action, observacion })
      });
      $('decisionModal').classList.add('hidden');
      $('caseModal').classList.add('hidden');
      showToast(`Decisión ${action.toLowerCase()} registrada para ${code}.`, 'success');
      await loadCases();
      await loadDashboard();
    } catch (error) { showToast(error.message, 'error'); }
    finally { setButtonBusy(button, false); }
  }

  async function runEvaluation() {
    const button = $('evaluateButton');
    if (!window.confirm('¿Deseas reevaluar los casos elegibles? Los casos tomados por analistas deben conservar su estado.')) return;
    setButtonBusy(button, true, 'Reevaluando…');
    try {
      const result = await api('/api/casos/evaluar', { method: 'POST' });
      showToast('La reevaluación terminó. Revisa el resumen devuelto por el servidor.', 'success');
      $('dashboardAlert').textContent = `Resultado de reevaluación: ${JSON.stringify(result)}`;
      $('dashboardAlert').classList.remove('hidden');
      await loadCases(); await loadDashboard();
    } catch (error) { showToast(error.message, 'error'); }
    finally { setButtonBusy(button, false); }
  }

  function showUploadResult(data, kind = 'success') {
    const box = $('uploadResult');
    box.className = `result-box ${kind}`;
    box.textContent = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
    box.classList.remove('hidden');
  }

  async function validateFile(event) {
    event.preventDefault();
    const input = $('excelFile');
    const file = input.files && input.files[0];
    if (!file) { showToast('Selecciona un archivo .xlsx.', 'error'); return; }
    if (!file.name.toLowerCase().endsWith('.xlsx')) { showToast('Solo se aceptan archivos con extensión .xlsx.', 'error'); return; }
    state.pendingFile = file;
    const button = $('validateFileButton');
    setButtonBusy(button, true, 'Validando…');
    $('importFileButton').disabled = true;
    try {
      const form = new FormData(); form.append('archivo', file);
      const result = await api('/api/casos/carga/validar', { method: 'POST', body: form });
      showUploadResult(result, 'success');
      const serialized = JSON.stringify(result).toLowerCase();
      const explicitlyInvalid = getValue(result, ['valido', 'valid', 'apto']) === false || /"valido"\s*:\s*false|"valid"\s*:\s*false/.test(serialized) || getValue(result, ['errores', 'cantidadErrores'], null) > 0;
      $('importFileButton').disabled = explicitlyInvalid;
      showToast(explicitlyInvalid ? 'La validación encontró errores; corrige el archivo antes de importarlo.' : 'Validación completada. Revisa el resultado antes de importar.', explicitlyInvalid ? 'error' : 'success');
    } catch (error) {
      state.pendingFile = null;
      showUploadResult(error.message, 'error');
      showToast(error.message, 'error');
    } finally { setButtonBusy(button, false); }
  }

  async function importFile() {
    const file = state.pendingFile || ($('excelFile').files && $('excelFile').files[0]);
    if (!file) { showToast('Selecciona y valida un archivo antes de importarlo.', 'error'); return; }
    if (!window.confirm(`Vas a importar “${file.name}”. Esta operación puede cambiar los datos del entorno. ¿Continuar?`)) return;
    const button = $('importFileButton'); setButtonBusy(button, true, 'Importando…');
    try {
      const form = new FormData(); form.append('archivo', file);
      const result = await api('/api/casos/carga', { method: 'POST', body: form });
      showUploadResult(result, 'success');
      showToast('El backend respondió a la importación. Verifica el resumen y las bandejas.', 'success');
      state.pendingFile = null; $('importFileButton').disabled = true;
      await loadDashboard();
    } catch (error) { showUploadResult(error.message, 'error'); showToast(error.message, 'error'); }
    finally { setButtonBusy(button, false); }
  }

  function isBatchObject(data) {
    if (!data || typeof data !== 'object') return null;
    return getValue(data, ['lote', 'paymentBatch', 'batch', 'resultado'], data);
  }

  function displayBatch(data, mode) {
    const result = isBatchObject(data) || {};
    state.latestBatch = result;
    const batchId = getValue(result, ['idLote', 'loteId', 'id', 'identificadorLote', 'batchId'], '');
    const batchCode = getValue(result, ['codigoLote', 'numeroLote', 'identificador', 'codigo'], '');
    const quantity = getValue(result, ['cantidadCasos', 'cantidad', 'totalCasos', 'numeroCasos'], '—');
    const totalValue = getValue(result, ['valorTotal', 'totalValor', 'montoTotal', 'valor'], '');
    const status = getValue(result, ['estado', 'estadoLote', 'status'], mode === 'paid' ? 'PAGADO' : 'GENERADO');
    const date = getValue(result, ['fechaCreacion', 'fecha', 'fechaGeneracion', 'createdAt'], '');
    $('batchResult').className = 'batch-result';
    $('batchResult').innerHTML = `<div class="detail-grid">
      <div class="detail-field"><span>Identificador</span><strong>${escapeHtml(text(batchCode || batchId))}</strong></div>
      <div class="detail-field"><span>Estado</span><strong>${escapeHtml(prettyLabel(status))}</strong></div>
      <div class="detail-field"><span>Casos incluidos</span><strong>${escapeHtml(text(quantity))}</strong></div>
      <div class="detail-field"><span>Valor total</span><strong>${escapeHtml(totalValue === '' ? 'No informado' : currency(totalValue))}</strong></div>
      <div class="detail-field"><span>Fecha de generación</span><strong>${escapeHtml(text(date))}</strong></div>
      <div class="detail-field"><span>ID numérico para confirmar</span><strong>${escapeHtml(text(batchId, 'No informado'))}</strong></div>
      </div><details class="detail-section"><summary>Respuesta completa del servidor</summary><pre class="result-json">${escapeHtml(JSON.stringify(data, null, 2))}</pre></details>`;
    if (batchId !== '') {
      $('batchIdInput').value = batchId;
      $('payBatchControls').classList.remove('hidden');
    } else {
      $('payBatchControls').classList.toggle('hidden', state.role !== 'ADMIN');
      if (state.role === 'ADMIN') $('payBatchControls').classList.remove('hidden');
    }
  }

  async function generateBatch() {
    if (!window.confirm('¿Generar un lote con todos los casos aprobados elegibles ahora?')) return;
    const button = $('generateBatchButton'); setButtonBusy(button, true, 'Generando…');
    try {
      const result = await api('/api/lotes', { method: 'POST' });
      displayBatch(result, 'created');
      showToast('Lote generado. Verifica cantidad, valor e identificador.', 'success');
      await loadDashboard();
    } catch (error) { showToast(error.message, 'error'); }
    finally { setButtonBusy(button, false); }
  }

  async function payBatch() {
    const id = $('batchIdInput').value.trim();
    if (!/^\d+$/.test(id)) { showToast('Ingresa el ID numérico del lote.', 'error'); return; }
    if (state.role !== 'ADMIN') { showToast('Solo ADMIN puede confirmar pagos.', 'error'); return; }
    if (!window.confirm(`Confirmar el pago simulado del lote ${id}. ¿Continuar?`)) return;
    const button = $('payBatchButton'); setButtonBusy(button, true, 'Confirmando…');
    try {
      const result = await api(`/api/lotes/${encodeURIComponent(id)}/pagar`, { method: 'PATCH' });
      displayBatch(result, 'paid');
      showToast('El backend confirmó la operación. Verifica el estado devuelto.', 'success');
      await loadDashboard();
    } catch (error) { showToast(error.message, 'error'); }
    finally { setButtonBusy(button, false); }
  }

  async function loadParameters() {
    $('parametersList').innerHTML = '<p class="table-empty">Cargando parámetros…</p>';
    if (state.role !== 'ADMIN') return;
    try {
      const result = await api('/api/parametros');
      const params = firstArray(result);
      $('parametersCaption').textContent = `${params.length} parámetro(s) disponibles`;
      if (!params.length) {
        $('parametersList').innerHTML = `<pre class="result-json">${escapeHtml(JSON.stringify(result, null, 2))}</pre>`;
        return;
      }
      $('parametersList').innerHTML = params.map((item) => {
        const key = text(getValue(item, ['clave', 'key', 'nombre', 'codigo'], ''));
        const value = text(getValue(item, ['valor', 'parameterValue', 'valorParametro', 'value'], ''));
        const description = text(getValue(item, ['descripcion', 'descripcionParametro', 'ayuda', 'description'], ''));
        return `<form class="parameter-row" data-parameter-form="${escapeHtml(key)}"><div class="parameter-info"><strong>${escapeHtml(key)}</strong><small>${escapeHtml(description)}</small></div><div><label for="param-${escapeHtml(key)}">Valor actual / nuevo</label><input id="param-${escapeHtml(key)}" name="valor" maxlength="4000" required value="${escapeHtml(value)}"></div><div class="parameter-reason"><label for="reason-${escapeHtml(key)}">Motivo del cambio</label><input id="reason-${escapeHtml(key)}" name="motivo" minlength="${MIN_REASON}" maxlength="500" required placeholder="Justifica el cambio (mín. 10 caracteres)"></div><button class="button button-outline" type="submit">Guardar</button></form>`;
      }).join('');
    } catch (error) {
      $('parametersCaption').textContent = 'No fue posible consultar los parámetros';
      $('parametersList').innerHTML = `<p class="table-empty">${escapeHtml(error.message)}</p>`;
    }
  }

  async function saveParameter(form) {
    const key = form.dataset.parameterForm;
    const data = new FormData(form);
    const valor = String(data.get('valor') ?? '').trim();
    const motivo = String(data.get('motivo') ?? '').trim();
    if (motivo.length < MIN_REASON) { showToast('El motivo debe tener al menos 10 caracteres.', 'error'); return; }
    const button = form.querySelector('button[type="submit"]'); setButtonBusy(button, true, 'Guardando…');
    try {
      const result = await api(`/api/parametros/${encodeURIComponent(key)}`, { method: 'PUT', body: JSON.stringify({ valor, motivo }) });
      showToast(`Parámetro ${key} actualizado.`, 'success');
      await loadParameters();
      if (result) $('parametersCaption').textContent += ' · Último cambio guardado';
    } catch (error) { showToast(error.message, 'error'); }
    finally { if (button.isConnected) setButtonBusy(button, false); }
  }

  async function exportCsv() {
    const button = $('exportButton'); setButtonBusy(button, true, 'Preparando…');
    try {
      const response = await api('/api/reportes/exportar');
      if (!(response instanceof Response)) throw new Error('La exportación no devolvió un archivo descargable.');
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = 'resultados_reclamaciones_prueba.csv';
      document.body.appendChild(a); a.click(); a.remove();
      URL.revokeObjectURL(url);
      showToast('La descarga del archivo de resultados se inició.', 'success');
    } catch (error) { showToast(error.message, 'error'); }
    finally { setButtonBusy(button, false); }
  }

  async function login(event) {
    event.preventDefault();
    const button = $('loginButton');
    $('loginError').textContent = '';
    const username = $('username').value.trim();
    const password = $('password').value;
    setButtonBusy(button, true, 'Validando…');
    try {
      const response = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) });
      const token = getValue(response, ['token', 'accessToken', 'jwt', 'access_token']);
      if (!token || typeof token !== 'string') throw new Error('El login respondió sin un token JWT reconocible. Revisa el contrato de /api/auth/login.');
      state.token = token;
      state.username = text(getValue(response, ['username', 'usuario', 'nombreUsuario'], username));
      state.role = deriveRole(response, token, username);
      sessionStorage.setItem('sura_jwt', state.token);
      sessionStorage.setItem('sura_username', state.username);
      sessionStorage.setItem('sura_role', state.role);
      enterApplication();
      showToast(`Sesión iniciada como ${state.role}.`, 'success');
    } catch (error) {
      $('loginError').textContent = error.message;
    } finally { setButtonBusy(button, false); }
  }

  function bindEvents() {
    $('loginForm').addEventListener('submit', login);
    $('logoutButton').addEventListener('click', () => { clearSession(); showToast('Sesión cerrada.'); });
    document.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => showView(button.dataset.view)));
    document.querySelectorAll('[data-open-view]').forEach((button) => button.addEventListener('click', () => showView(button.dataset.openView)));
    $('mobileMenuButton').addEventListener('click', () => $('sidebar').classList.toggle('open'));
    $('refreshDashboard').addEventListener('click', loadDashboard);
    $('applyFilters').addEventListener('click', () => { state.page = 0; loadCases(); });
    $('caseSearch').addEventListener('input', () => { state.page = 0; clearTimeout(window.__caseSearchTimer); window.__caseSearchTimer = setTimeout(loadCases, 250); });
    $('pageSize').addEventListener('change', () => { state.page = 0; loadCases(); });
    $('previousPage').addEventListener('click', () => { state.page = Math.max(0, state.page - 1); loadCases(); });
    $('nextPage').addEventListener('click', () => { if (state.page + 1 < state.totalPages) { state.page += 1; loadCases(); } });
    $('casesTableBody').addEventListener('click', (event) => {
      const button = event.target.closest('[data-case-detail]');
      if (button) openCaseDetail(button.dataset.caseDetail);
    });
    $('caseModalBody').addEventListener('click', (event) => {
      const take = event.target.closest('[data-take-case]');
      const decision = event.target.closest('[data-decision]');
      if (take) takeCase(take.dataset.takeCase);
      if (decision) openDecisionModal(decision.dataset.code, decision.dataset.decision);
    });
    $('closeCaseModal').addEventListener('click', () => $('caseModal').classList.add('hidden'));
    $('closeDecisionModal').addEventListener('click', () => $('decisionModal').classList.add('hidden'));
    $('cancelDecisionButton').addEventListener('click', () => $('decisionModal').classList.add('hidden'));
    $('decisionForm').addEventListener('submit', submitDecision);
    $('decisionObservation').addEventListener('input', () => $('observationCount').textContent = String($('decisionObservation').value.length));
    $('evaluateButton').addEventListener('click', runEvaluation);
    $('uploadForm').addEventListener('submit', validateFile);
    $('importFileButton').addEventListener('click', importFile);
    $('excelFile').addEventListener('change', (event) => {
      const file = event.target.files && event.target.files[0];
      $('fileName').textContent = file ? file.name : 'Arrastra tu archivo aquí o selecciónalo';
      $('importFileButton').disabled = true;
      state.pendingFile = null;
      $('uploadResult').classList.add('hidden');
    });
    const drop = $('dropZone');
    ['dragenter', 'dragover'].forEach((name) => drop.addEventListener(name, (event) => { event.preventDefault(); drop.classList.add('dragover'); }));
    ['dragleave', 'drop'].forEach((name) => drop.addEventListener(name, (event) => { event.preventDefault(); drop.classList.remove('dragover'); }));
    drop.addEventListener('drop', (event) => {
      const file = event.dataTransfer.files && event.dataTransfer.files[0];
      if (!file) return;
      const transfer = new DataTransfer(); transfer.items.add(file); $('excelFile').files = transfer.files;
      $('excelFile').dispatchEvent(new Event('change', { bubbles: true }));
    });
    $('generateBatchButton').addEventListener('click', generateBatch);
    $('payBatchButton').addEventListener('click', payBatch);
    $('reloadParametersButton').addEventListener('click', loadParameters);
    $('parametersList').addEventListener('submit', (event) => {
      const form = event.target.closest('[data-parameter-form]');
      if (form) { event.preventDefault(); saveParameter(form); }
    });
    $('exportButton').addEventListener('click', exportCsv);
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        $('caseModal').classList.add('hidden'); $('decisionModal').classList.add('hidden'); $('sidebar').classList.remove('open');
      }
    });
  }

  bindEvents();
  if (state.token) {
    // Reutilizar la sesión de esta pestaña, pero validar inmediatamente que el JWT siga siendo válido.
    enterApplication();
    api('/api/reportes/resumen').catch(() => { /* api() limpiará el token cuando reciba 401 */ });
  }
})();
