import cytoscape, { type Core, type ElementDefinition } from 'cytoscape'
import './style.css'

type Node = { id: string; kind: string; language: string; name: string; qualified_name?: string | null; file: string; parent?: string | null; line_range?: { start: number; end: number } | null }
type Edge = { from: string; to: string; relationship: string; provenance: string; resolution: string }
type Lease = { file: string; lease_id: string; generation: string; daemon_epoch: string; session_id: string; expected_file_hash: string; expires_at: string; remaining_ms: number }
type Event = { event_id: string; daemon_epoch: string; timestamp: string; session_id?: string | null; actor?: string | null; agent_label?: string | null; role?: string; operation_id?: string | null; event_type: string; tool_name?: string | null; snapshot_id?: string | null; node_ids?: string[]; file_paths?: string[]; status?: string; duration_ms?: number; lease?: Lease; edit_id?: string | null; failure_code?: string; error?: { code?: string } }
type EditPreview = { edit_id: string; node_id: string; file: string; snapshot_id: string; base_hash: string; new_hash: string; byte_span: { start: number; end: number }; preview: { before: string; after: string; truncated: boolean }; syntax_checked: boolean; project_tests_run: boolean; committed: boolean; expires_in_ms: number }
type State = { daemon_epoch: string; repository_name: string; cursor: string; snapshot: { snapshot_id: string; generated_at: string; analysis_engine: string; semantic_level: string; indexing: string; diagnostics: unknown[]; omitted_file_count: number; nodes: Node[]; edges: Edge[] }; sessions: Array<{ session_id: string; agent_label: string; role: string; connection: string; last_seen: string; active_operation_id?: string | null }>; leases: Lease[]; capabilities: { languages: string[]; coordinated_writes: boolean; disabled_tools: string[] } }

const app = document.querySelector<HTMLDivElement>('#app')!
let state: State | null = null
let graph: Core | null = null
let selectedNode: Node | null = null
let followedSession: string | null = null
let language = 'all'
let query = ''
let streamAbort: AbortController | null = null
let heartbeatTimer: number | null = null
let leaseTimer: number | null = null
let lastDeliveredCursor: string | null = null
let reconciling = false
let reconcileAgain = false
let listOpen = false
let activityHistoryReset = false
const activities: Event[] = []
const sessionColors = ['#8b5cf6', '#22c55e', '#f59e0b', '#38bdf8', '#f472b6', '#a3e635']
const sessionColorMap = new Map<string, string>()

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string) {
  const item = document.createElement(tag)
  if (className) item.className = className
  if (text !== undefined) item.textContent = text
  return item
}

function requestHeaders(epoch?: string) {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (epoch) headers['X-GraphHarness-Epoch'] = epoch
  return headers
}

class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

async function api<T>(url: string, init: RequestInit = {}, epoch = state?.daemon_epoch): Promise<T> {
  const response = await fetch(url, { ...init, credentials: 'same-origin', headers: { ...requestHeaders(epoch), ...(init.headers || {}) } })
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new ApiError(body?.error?.message || body?.error?.code || `Request failed (${response.status})`, response.status)
  return body as T
}

function relativeTime(value?: string) {
  if (!value) return 'Unknown'
  const seconds = Math.max(0, Math.round((Date.now() - Date.parse(value)) / 1000))
  return seconds < 60 ? `${seconds}s ago` : seconds < 3600 ? `${Math.round(seconds / 60)}m ago` : new Date(value).toLocaleTimeString()
}

function agentColor(sessionId?: string | null) {
  if (!sessionId) return '#64748b'
  const known = sessionColorMap.get(sessionId)
  if (known) return known
  const color = sessionColors[sessionColorMap.size % sessionColors.length]
  sessionColorMap.set(sessionId, color)
  return color
}

function setConnection(message: string, kind: 'ok' | 'warn' | 'error' = 'warn') {
  const badge = document.querySelector<HTMLElement>('#connection')
  if (badge) { badge.textContent = message; badge.dataset.kind = kind }
}

function statusText() {
  if (!state) return 'Waiting for observer access'
  const snapshot = state.snapshot
  if (snapshot.indexing === 'failed') return 'Indexing failed — displaying the last available graph'
  if (snapshot.indexing !== 'idle') return `Indexing ${snapshot.indexing} — graph may update shortly`
  const quality = `${snapshot.analysis_engine} · ${snapshot.semantic_level}`
  if (snapshot.diagnostics.length) return `${quality} · ${snapshot.diagnostics.length} diagnostic${snapshot.diagnostics.length === 1 ? '' : 's'}`
  return `${quality} · ${snapshot.indexing}`
}

function bootstrap() {
  app.replaceChildren()
  const shell = el('main', 'shell')
  const top = el('header', 'topbar')
  const brand = el('div', 'brand')
  brand.append(el('span', 'mark', 'GH'), el('div', undefined, undefined))
  brand.lastElementChild!.append(el('strong', undefined, 'GraphHarness'), el('span', 'subtle', ' Live'))
  const repository = el('span', 'repository', 'Connecting to local daemon…')
  const connection = el('span', 'connection', 'Observer access required'); connection.id = 'connection'; connection.dataset.kind = 'warn'
  const actions = el('div', 'top-actions')
  const listToggle = button('Code list', toggleList); listToggle.id = 'list-toggle'; listToggle.setAttribute('aria-expanded', 'false')
  actions.append(languageSelect(), searchInput(), button('Fit graph', fitGraph), listToggle)
  top.append(brand, repository, actions, connection)

  const pairing = el('section', 'pairing card')
  pairing.id = 'pairing'
  pairing.append(el('p', 'eyebrow', 'LOCAL OBSERVER ACCESS'), el('h1', undefined, 'Pair this browser'), el('p', 'pair-copy', 'GraphHarness keeps observer access in an HttpOnly local cookie. This page never receives or stores a daemon secret.'), button('Start pairing', beginPairing, 'primary'))

  const workspace = el('section', 'workspace hidden'); workspace.id = 'workspace'
  const canvasCard = el('section', 'canvas-card')
  const canvasHeader = el('div', 'canvas-header')
  const title = el('div'); title.append(el('h1', undefined, 'Code map'), el('p', 'muted', 'Observed structure and tool activity'))
  const quality = button('No snapshot yet', renderDiagnostics, 'quality'); quality.id = 'quality'; quality.title = 'Inspect analysis quality and diagnostics'
  canvasHeader.append(title, quality)
  const graphRoot = el('div', 'graph'); graphRoot.id = 'graph'
  const graphEmpty = el('div', 'graph-empty', 'No graph is available yet. The daemon will publish a snapshot when indexing completes.'); graphEmpty.id = 'graph-empty'
  canvasCard.append(canvasHeader, graphRoot, graphEmpty)

  const inspector = el('aside', 'inspector card'); inspector.id = 'inspector'; inspector.append(el('h2', undefined, 'Inspector'), el('p', 'muted', 'Select a file or symbol to inspect its source and relationships.'))
  const activity = el('aside', 'activity card'); activity.id = 'activity'; activity.append(el('h2', undefined, 'Observed activity'), el('p', 'muted', 'Only daemon-recorded actions appear here. Quiet time does not describe private work.'))
  const list = el('section', 'list-panel card hidden'); list.id = 'list-panel'; list.setAttribute('aria-label', 'Accessible code list'); list.append(el('h2', undefined, 'Accessible code list'), el('p', 'muted', 'Search results and graph nodes are available as keyboard-operable buttons.'))
  workspace.append(canvasCard, inspector, activity, list)
  shell.append(top, pairing, workspace)
  app.append(shell)
}

function toggleList() {
  listOpen = !listOpen
  const panel = document.querySelector('#list-panel')!
  const toggle = document.querySelector<HTMLButtonElement>('#list-toggle')!
  panel.classList.toggle('hidden', !listOpen); toggle.setAttribute('aria-expanded', String(listOpen))
  if (listOpen) panel.querySelector<HTMLElement>('button')?.focus()
}

function fitGraph() {
  if (!graph) return
  const visible = graph.elements().not('.filtered')
  if (!visible.nonempty()) return
  graph.fit(visible, 52)
  graph.center(visible)
}

function button(text: string, onClick: () => void, className = '') {
  const item = el('button', `button ${className}`, text); item.type = 'button'; item.addEventListener('click', onClick); return item
}

function languageSelect() {
  const select = el('select', 'language-filter')
  select.setAttribute('aria-label', 'Filter by language')
  select.addEventListener('change', () => { language = select.value; renderGraph(); renderList() })
  return select
}

function searchInput() {
  const input = el('input', 'search') as HTMLInputElement
  input.type = 'search'; input.placeholder = 'Search symbols or files'; input.setAttribute('aria-label', 'Search symbols or files')
  input.addEventListener('input', () => { query = input.value.trim().toLocaleLowerCase(); renderGraph(); renderList() })
  return input
}

async function beginPairing() {
  try {
    const pair = await api<{ pairing_code: string; expires_in_ms?: number }>('/observer/pair', { method: 'POST', body: '{}' }, undefined)
    const panel = document.querySelector('#pairing')!
    panel.replaceChildren(el('p', 'eyebrow', 'BROWSER PAIRING PENDING'), el('h1', undefined, 'Approve this browser locally'), el('p', 'pair-copy', 'Run this command in a terminal from the checkout being served:'), el('code', 'command', `graphharness authorize-browser ROOT ${pair.pairing_code}`), el('p', 'muted', 'The code expires in about one minute. It identifies this browser only; it is not a daemon credential.'))
    await pollClaim()
  } catch (error) { setPairingError(error) }
}

async function pollClaim(): Promise<void> {
  try {
    const response = await fetch('/observer/claim', { method: 'POST', credentials: 'same-origin', headers: requestHeaders(), body: '{"schema_version":1}' })
    if (response.status === 202) { window.setTimeout(() => void pollClaim(), 1000); return }
    if (response.status === 429) {
      const retryAfter = Number(response.headers.get('Retry-After'))
      window.setTimeout(() => void pollClaim(), Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : 1000)
      return
    }
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new Error(body?.error?.message || body?.error?.code || 'Pairing could not be claimed')
    await refreshState()
  } catch (error) { setPairingError(error) }
}

function setPairingError(error: unknown) {
  const panel = document.querySelector('#pairing')!
  panel.replaceChildren(el('p', 'eyebrow', 'PAIRING UNAVAILABLE'), el('h1', undefined, 'Could not pair this browser'), el('p', 'pair-copy', error instanceof Error ? error.message : 'Unexpected pairing failure.'), button('Try again', beginPairing, 'primary'))
  setConnection('Pairing unavailable', 'error')
}

async function refreshState(restartStream = false, authoritativeReset = false) {
  try {
    const fresh = await api<State>('/state', { method: 'GET' })
    const hadState = state !== null
    const changedEpoch = state && state.daemon_epoch !== fresh.daemon_epoch
    state = fresh
    document.querySelector('.repository')!.textContent = fresh.repository_name
    document.querySelector('#pairing')?.classList.add('hidden')
    document.querySelector('#workspace')?.classList.remove('hidden')
    document.querySelector<HTMLElement>('#workspace')!.dataset.snapshotId = fresh.snapshot.snapshot_id
    setConnection('Live observer', 'ok')
    syncLanguageOptions()
    if (changedEpoch || authoritativeReset) {
      activities.splice(0)
      activityHistoryReset = true
      lastDeliveredCursor = fresh.cursor
    }
    if (lastDeliveredCursor === null) lastDeliveredCursor = fresh.cursor
    renderAll()
    startHeartbeat()
    startLeaseTicker()
    if (restartStream || changedEpoch || !hadState) subscribe()
  } catch (error) {
    if (error instanceof ApiError && [401, 403, 409].includes(error.status)) { resetObserver(); return }
    setConnection('Reconnecting…', 'warn')
    if (state) window.setTimeout(() => void refreshState(restartStream, authoritativeReset), 2000)
    else setPairingError(error)
  }
}

function resetObserver() {
  streamAbort?.abort(); streamAbort = null
  if (heartbeatTimer !== null) { window.clearInterval(heartbeatTimer); heartbeatTimer = null }
  if (leaseTimer !== null) { window.clearInterval(leaseTimer); leaseTimer = null }
  state = null; selectedNode = null; followedSession = null; lastDeliveredCursor = null; activities.splice(0); activityHistoryReset = false
  document.querySelector('#workspace')?.classList.add('hidden')
  const panel = document.querySelector('#pairing')!
  panel.classList.remove('hidden')
  panel.replaceChildren(el('p', 'eyebrow', 'LOCAL OBSERVER ACCESS'), el('h1', undefined, 'Pair this browser'), el('p', 'pair-copy', 'GraphHarness keeps observer access in an HttpOnly local cookie. This page never receives or stores a daemon secret.'), button('Start pairing', beginPairing, 'primary'))
  setConnection('Observer access required', 'warn')
}

function startHeartbeat() {
  if (heartbeatTimer !== null) return
  heartbeatTimer = window.setInterval(() => {
    if (!state) return
    void api('/sessions/heartbeat', { method: 'POST', body: JSON.stringify({ schema_version: 1 }) }).catch(error => {
      if (error instanceof ApiError && [401, 403, 409].includes(error.status)) resetObserver()
    })
  }, 10_000)
}

function startLeaseTicker() {
  if (!state?.leases.length) { if (leaseTimer !== null) { window.clearInterval(leaseTimer); leaseTimer = null }; return }
  if (leaseTimer !== null) return
  leaseTimer = window.setInterval(updateLeaseCountdowns, 1000)
}

function updateLeaseCountdowns() {
  if (!state) return
  for (const lease of state.leases) {
    const text = `Reserved by ${sessionLabel(lease.session_id)} · ${leaseCountdown(lease)}`
    document.querySelectorAll<HTMLElement>(`[data-lease-id="${CSS.escape(lease.lease_id)}"]`).forEach(item => {
      item.textContent = item.dataset.leaseLine === 'true' ? `${lease.file} · holder: ${sessionLabel(lease.session_id)} · ${leaseCountdown(lease)}` : text
    })
  }
}

async function checkExistingObserver() {
  try {
    const response = await fetch('/state', { credentials: 'same-origin' })
    if (response.status === 401 || response.status === 403) return
    if (!response.ok) throw new Error(`State unavailable (${response.status})`)
    state = await response.json() as State
    document.querySelector('.repository')!.textContent = state.repository_name
    document.querySelector('#pairing')?.classList.add('hidden')
    document.querySelector('#workspace')?.classList.remove('hidden')
    document.querySelector<HTMLElement>('#workspace')!.dataset.snapshotId = state.snapshot.snapshot_id
    setConnection('Live observer', 'ok')
    lastDeliveredCursor = state.cursor
    syncLanguageOptions(); renderAll(); startHeartbeat(); startLeaseTicker(); subscribe()
  } catch {
    setConnection('Daemon unavailable', 'warn')
  }
}

function syncLanguageOptions() {
  const select = document.querySelector<HTMLSelectElement>('.language-filter')!
  const available = state?.capabilities.languages || []
  const current = select.value || 'all'
  select.replaceChildren(new Option('All languages', 'all'), ...available.map(item => new Option(item, item)))
  select.value = available.includes(current) || current === 'all' ? current : 'all'
  language = select.value
}

function visibleNodes() {
  if (!state) return []
  return state.snapshot.nodes.filter(node => (language === 'all' || node.language === language) && (!query || `${node.name} ${node.qualified_name || ''} ${node.file}`.toLocaleLowerCase().includes(query)))
}

function canvasLabel(node: Node) {
  if (node.kind === 'file') return node.file.split('/').pop() || node.name
  const source = node.qualified_name || node.name
  const concise = source.split('.').filter(Boolean).at(-1) || node.name
  return node.kind === 'method' && !concise.endsWith(')') ? `${concise}()` : concise
}

function fileGraphId(file: string) {
  return state?.snapshot.nodes.find(node => node.kind === 'file' && node.file === file)?.id || `file:${encodeURIComponent(file)}`
}

function leaseForFile(file: string) { return state?.leases.find(lease => lease.file === file) || null }

function sessionLabel(sessionId?: string | null) {
  if (!sessionId) return 'Unknown session'
  return state?.sessions.find(session => session.session_id === sessionId)?.agent_label || sessionId
}

function leaseCountdown(lease: Lease) {
  const expiry = Date.parse(lease.expires_at)
  const remaining = Number.isFinite(expiry) ? Math.max(0, expiry - Date.now()) : Math.max(0, lease.remaining_ms)
  return remaining <= 0 ? 'expiry pending' : `${Math.ceil(remaining / 1000)}s remaining`
}

function renderAll() { renderGraph(); renderInspector(); renderActivity(); renderList(); renderStatus() }
function renderStatus() {
  const quality = document.querySelector<HTMLButtonElement>('#quality')!; quality.textContent = statusText()
  quality.className = `quality ${state?.snapshot.indexing === 'failed' ? 'error-text' : ''}`
}

function renderDiagnostics() {
  const panel = document.querySelector<HTMLElement>('#inspector')!
  const snapshot = state?.snapshot
  panel.replaceChildren(el('h2', undefined, 'Analysis quality'))
  if (!snapshot) { panel.append(el('p', 'muted', 'No snapshot is available.')); return }
  panel.append(el('p', 'muted', `Backend: ${snapshot.analysis_engine}`), el('p', 'muted', `Relationship confidence: ${snapshot.semantic_level}`), el('p', 'muted', `Indexing: ${snapshot.indexing}`))
  if (!snapshot.diagnostics.length) { panel.append(el('p', 'muted', 'The daemon reported no diagnostics for this snapshot.')); return }
  panel.append(el('h3', undefined, 'Diagnostics'))
  for (const diagnostic of snapshot.diagnostics) panel.append(el('pre', 'diagnostic', typeof diagnostic === 'string' ? diagnostic : JSON.stringify(diagnostic, null, 2)))
}

function renderGraph() {
  const root = document.querySelector<HTMLElement>('#graph')!
  const empty = document.querySelector<HTMLElement>('#graph-empty')!
  if (!state?.snapshot.nodes.length) { empty.classList.remove('hidden'); return }
  empty.classList.add('hidden')
  const fileNodes = new Map<string, ElementDefinition>()
  const fileParents = new Map<string, string>()
  for (const node of state.snapshot.nodes) if (node.kind === 'file' && !fileParents.has(node.file)) fileParents.set(node.file, node.id)
  const elements: ElementDefinition[] = []
  for (const node of state.snapshot.nodes) {
    const parent = fileParents.get(node.file) || `file:${encodeURIComponent(node.file)}`
    if (!fileParents.has(node.file) && !fileNodes.has(parent)) fileNodes.set(parent, { data: { id: parent, label: node.file.split('/').pop() || node.file, kind: 'file', file: node.file, file_parent: true, synthetic: true } })
    const data: Record<string, unknown> = { id: node.id, label: canvasLabel(node), kind: node.kind, file: node.file, language: node.language }
    if (node.kind === 'file') data.file_parent = true
    else data.parent = parent
    elements.push({ data })
  }
  elements.unshift(...fileNodes.values())
  const ids = new Set(state.snapshot.nodes.map(node => node.id))
  for (const edge of state.snapshot.edges) if (ids.has(edge.from) && ids.has(edge.to)) elements.push({ data: { id: `${edge.from}:${edge.relationship}:${edge.to}`, source: edge.from, target: edge.to, relationship: edge.relationship, resolution: edge.resolution } })
  const newGraph = !graph
  if (!graph) graph = cytoscape({ container: root, elements, minZoom: 0.02, maxZoom: 1.8, layout: { name: 'preset', fit: true, padding: 42 }, style: [
    { selector: 'node', style: { 'background-color': '#293548', label: 'data(label)', color: '#cbd5e1', 'font-size': '10px', 'text-wrap': 'ellipsis', 'text-max-width': '84px', 'text-valign': 'bottom', 'text-margin-y': 8, width: 24, height: 24, 'border-width': 1, 'border-color': '#58708d' } },
    { selector: 'node[file_parent]', style: { shape: 'round-rectangle', width: 42, height: 26, 'background-color': '#18465f', 'border-color': '#38bdf8' } },
    { selector: 'node[file_parent].leased', style: { 'border-width': 3, 'border-color': '#f59e0b', 'background-color': '#5a3a16' } },
    { selector: 'edge', style: { width: 1, 'line-color': '#40526c', 'target-arrow-color': '#40526c', 'target-arrow-shape': 'triangle', 'curve-style': 'bezier', opacity: 0.75 } },
    { selector: '.selected', style: { 'border-width': 3, 'border-color': '#f8fafc', 'background-color': '#8b5cf6' } },
    { selector: '.active', style: { 'border-width': 3, 'border-color': '#f59e0b' } },
    { selector: '.dim', style: { opacity: 0.16 } },
    { selector: '.filtered', style: { display: 'none' } }
  ] })
  else {
    const wanted = new Set(elements.map(item => String(item.data.id)))
    graph.elements().forEach(item => { if (!wanted.has(item.id())) item.remove() })
    const present = new Set(graph.elements().map(item => item.id()))
    const added = graph.add(elements.filter(item => !present.has(String(item.data.id))))
    seedPositions(added)
  }
  graph.off('tap', 'node').on('tap', 'node', event => {
    const node = state!.snapshot.nodes.find(item => item.id === event.target.id())
    if (!node) return
    selectedNode = node; renderInspector(); highlight()
  })
  const visible = new Set(visibleNodes().map(node => node.id))
  const visibleFiles = new Set(visibleNodes().map(node => fileGraphId(node.file)))
  graph.elements().removeClass('filtered')
  graph.nodes().forEach(item => { if (!visible.has(item.id()) && !visibleFiles.has(item.id())) item.addClass('filtered') })
  graph.edges().forEach(item => { if (!visible.has(item.source().id()) || !visible.has(item.target().id())) item.addClass('filtered') })
  graph.nodes('[file_parent]').removeClass('leased')
  for (const lease of state.leases) graph.$id(fileGraphId(lease.file)).addClass('leased')
  if (newGraph) { seedPositions(graph.elements()); requestAnimationFrame(fitGraph) }
  highlight()
}

function seedPositions(elements: ReturnType<Core['add']>) {
  if (!graph || !state) return
  const files = [...new Set(state.snapshot.nodes.map(node => node.file))]
  const columns = Math.max(1, Math.ceil(Math.sqrt(files.length)))
  const byFile = new Map(files.map(file => [file, state!.snapshot.nodes.filter(node => node.file === file && node.kind !== 'file')]))
  const widths = Array(columns).fill(330) as number[]
  const heights = Array(Math.ceil(files.length / columns)).fill(270) as number[]
  files.forEach((file, index) => {
    const count = byFile.get(file)!.length
    const localColumns = Math.max(1, Math.ceil(Math.sqrt(count)))
    widths[index % columns] = Math.max(widths[index % columns], localColumns * 120 + 100)
    heights[Math.floor(index / columns)] = Math.max(heights[Math.floor(index / columns)], Math.ceil(count / localColumns) * 90 + 100)
  })
  const positions = new Map<string, { x: number; y: number }>()
  files.forEach((file, index) => {
    const nodes = byFile.get(file)!
    const localColumns = Math.max(1, Math.ceil(Math.sqrt(nodes.length)))
    const x = 100 + widths.slice(0, index % columns).reduce((a, b) => a + b, 0)
    const y = 100 + heights.slice(0, Math.floor(index / columns)).reduce((a, b) => a + b, 0)
    positions.set(fileGraphId(file), { x, y })
    nodes.forEach((node, local) => positions.set(node.id, { x: x + (local % localColumns) * 120, y: y + Math.floor(local / localColumns) * 90 }))
  })
  elements.nodes().filter(node => !node.isParent()).forEach(node => {
    const position = positions.get(node.id())
    if (position) node.position(position)
  })
}

function highlight() {
  if (!graph) return
  graph.elements().removeClass('selected active dim')
  if (selectedNode) graph.$id(selectedNode.id).addClass('selected').closedNeighborhood().removeClass('dim')
  const snapshotId = state?.snapshot.snapshot_id
  if (followedSession && snapshotId) activities.filter(event => event.session_id === followedSession && event.snapshot_id === snapshotId).slice(0, 1).flatMap(event => event.node_ids || []).forEach(id => graph?.$id(id).addClass('active'))
}

function renderInspector() {
  const panel = document.querySelector<HTMLElement>('#inspector')!
  panel.replaceChildren(el('h2', undefined, 'Inspector'))
  if (!selectedNode) { panel.append(el('p', 'muted', 'Select a file or symbol to inspect its source and relationships.')); return }
  panel.append(el('span', 'badge', `${selectedNode.language} · ${selectedNode.kind}`), el('h3', undefined, selectedNode.qualified_name || selectedNode.name), el('p', 'path', selectedNode.file))
  const lease = leaseForFile(selectedNode.file)
  if (lease) { const badge = el('p', 'lease-badge', `Reserved by ${sessionLabel(lease.session_id)} · ${leaseCountdown(lease)}`); badge.dataset.leaseId = lease.lease_id; panel.append(badge) }
  if (selectedNode.line_range) panel.append(el('p', 'muted', `Lines ${selectedNode.line_range.start}–${selectedNode.line_range.end}`))
  panel.append(button('Load source', () => void inspectSource(selectedNode!), 'secondary'))
}

async function inspectSource(node: Node) {
  const panel = document.querySelector<HTMLElement>('#inspector')!
  panel.querySelector('.source,.loading-source,.error-text')?.remove()
  const loading = el('p', 'muted loading-source', 'Loading source from the daemon…'); panel.append(loading)
  try {
    const reply = await api<{ result: { source?: string; snapshot_id?: string; file_hash?: string } }>('/inspect', { method: 'POST', body: JSON.stringify({ schema_version: 1, request_id: crypto.randomUUID(), name: 'get_source', arguments: { node_id: node.id, include_context: 0 } }) })
    const source = reply.result.source
    if (!source) throw new Error('The daemon returned no source for this node.')
    if (selectedNode?.id !== node.id) return
    loading.remove()
    const pre = el('pre', 'source'); pre.textContent = source
    const existing = panel.querySelector('.source'); existing?.remove(); panel.append(pre)
  } catch (error) {
    loading.remove()
    const note = el('p', 'error-text', error instanceof Error ? error.message : 'Source inspection failed.'); panel.append(note)
  }
}

function renderActivity() {
  const panel = document.querySelector<HTMLElement>('#activity')!
  panel.replaceChildren(el('h2', undefined, 'Observed activity'), el('p', 'muted', 'Daemon-recorded calls and browser inspections. Quiet time does not describe private work.'))
  const sessions = el('div', 'sessions')
  for (const session of state?.sessions || []) {
    const sessionButton = button(`${session.agent_label} · ${session.connection}`, () => { followedSession = followedSession === session.session_id ? null : session.session_id; renderActivity(); highlight() }, `session ${followedSession === session.session_id ? 'following' : ''}`)
    sessionButton.style.setProperty('--agent', agentColor(session.session_id)); sessionButton.title = `Last seen ${relativeTime(session.last_seen)}`; sessions.append(sessionButton)
  }
  panel.append(sessions)
  if (activityHistoryReset) panel.append(el('p', 'muted', 'Earlier activity is unavailable after an authoritative stream reset.'))
  const list = el('ol', 'activity-list')
  for (const event of activities.slice(0, 14)) {
    const item = el('li', `activity-item ${event.role === 'observer' ? 'observer' : ''}`)
    item.dataset.eventId = event.event_id
    item.dataset.eventTimestamp = event.timestamp
    item.dataset.eventType = event.event_type
    if (event.tool_name) item.dataset.toolName = event.tool_name
    item.style.setProperty('--agent', agentColor(event.session_id)); item.append(el('span', 'activity-dot'), el('div', undefined, undefined))
    const details = item.lastElementChild!
    details.append(el('strong', undefined, eventTitle(event)), el('span', 'muted', eventDetail(event)))
    if ((event.event_type === 'edit_planned' || event.event_type === 'edit_applied') && event.edit_id) {
      const preview = button('View diff', () => void showEditPreview(event.edit_id!, event.event_type === 'edit_planned' ? 'edit_planned' : 'edit_applied'), 'event-action')
      preview.setAttribute('aria-label', `View ${event.event_type === 'edit_planned' ? 'planned' : 'applied'} edit diff`)
      details.append(preview)
    }
    if (event.node_ids?.length) item.addEventListener('click', () => inspectEventNode(event))
    list.append(item)
  }
  if (!activities.length) list.append(el('li', 'muted', 'No observed activity yet.'))
  panel.append(list)
  renderLeases(panel)
  panel.scrollTop = 0
}

function renderLeases(panel: HTMLElement) {
  const leases = state?.leases || []
  const heading = el('h3', 'lease-title', leases.length ? 'File reservations' : state?.capabilities.coordinated_writes ? 'File reservations' : 'Coordinated writes unavailable')
  panel.append(heading)
  if (!state?.capabilities.coordinated_writes && !leases.length) { panel.append(el('p', 'muted', 'This read-only milestone does not reserve or apply edits.')); return }
  if (!leases.length) { panel.append(el('p', 'muted', 'No active file reservations.')); return }
  for (const lease of leases) { const line = el('p', 'lease', `${lease.file} · holder: ${sessionLabel(lease.session_id)} · ${leaseCountdown(lease)}`); line.dataset.leaseId = lease.lease_id; line.dataset.leaseLine = 'true'; panel.append(line) }
}

function eventTitle(event: Event) {
  if (event.event_type === 'edit_planned') return `${event.agent_label || 'External'} planned an edit`
  if (event.event_type === 'edit_applied') return `${event.agent_label || 'External'} applied an edit`
  if (event.event_type === 'lease_denied') return `${sessionLabel(event.actor || event.session_id)} reservation denied`
  if (event.event_type.startsWith('lease_') && event.lease) return `${sessionLabel(event.lease.session_id)} ${event.event_type.replace('lease_', 'reservation ')}`
  return `${event.agent_label || 'External'} ${event.event_type.replaceAll('_', ' ')}`
}

async function showEditPreview(editId: string, eventType: 'edit_planned' | 'edit_applied') {
  const panel = document.querySelector<HTMLElement>('#inspector')!
  panel.replaceChildren(el('h2', undefined, eventType === 'edit_planned' ? 'Planned edit' : 'Applied edit'), el('p', 'muted', 'Loading retained plan preview…'))
  try {
    const response = await api<{ result: EditPreview }>(`/edits/${encodeURIComponent(editId)}`, { method: 'GET' })
    renderEditPreview(response.result, eventType)
  } catch (error) {
    panel.replaceChildren(el('h2', undefined, 'Edit preview unavailable'), el('p', 'muted', error instanceof ApiError && error.status === 404 ? 'This retained plan has expired or is no longer available.' : error instanceof Error ? error.message : 'The retained plan could not be loaded.'))
  }
}

function renderEditPreview(edit: EditPreview, eventType: 'edit_planned' | 'edit_applied') {
  const panel = document.querySelector<HTMLElement>('#inspector')!
  panel.replaceChildren(el('h2', undefined, eventType === 'edit_planned' ? 'Planned edit' : 'Applied edit'), el('p', 'path', edit.file), el('p', 'muted', `Byte range ${edit.byte_span.start}–${edit.byte_span.end} · ${edit.committed ? 'committed' : 'not committed'}`))
  if (edit.preview.truncated) panel.append(el('p', 'preview-warning', 'Preview is truncated to the retained bounded diff.'))
  const diff = el('div', 'diff')
  const before = el('section', 'diff-before'); before.append(el('h3', undefined, 'Before'), el('pre', 'source', edit.preview.before))
  const after = el('section', 'diff-after'); after.append(el('h3', undefined, 'After'), el('pre', 'source', edit.preview.after))
  diff.append(before, after); panel.append(diff)
  panel.append(el('p', 'syntax-status', edit.syntax_checked ? 'Syntax checked.' : 'Syntax was not checked.'), el('p', 'tests-status', edit.project_tests_run ? 'Project tests ran for this preview.' : 'Syntax checking is not project-test evidence; project tests did not run.'))
}

function eventDetail(event: Event) {
  if (event.event_type === 'lease_denied' && event.lease) {
    const code = event.error?.code || event.failure_code
    return `holder: ${sessionLabel(event.lease.session_id)} · ${event.lease.file}${code ? ` · ${code}` : ''} · ${relativeTime(event.timestamp)}`
  }
  if (event.event_type.startsWith('lease_') && event.lease) return `${event.lease.file} · ${leaseCountdown(event.lease)} · ${relativeTime(event.timestamp)}`
  return `${event.tool_name || event.status || 'event'} · ${relativeTime(event.timestamp)}`
}

function inspectEventNode(event: Event) {
  if (!state || event.snapshot_id !== state.snapshot.snapshot_id) {
    const panel = document.querySelector<HTMLElement>('#inspector')!
    panel.replaceChildren(el('h2', undefined, 'Recorded activity'), el('p', 'muted', 'This event refers to a historical graph that is unavailable in the current snapshot.'))
    return
  }
  selectedNode = state.snapshot.nodes.find(node => node.id === event.node_ids![0]) || null
  renderInspector(); highlight()
}

function renderList() {
  const panel = document.querySelector<HTMLElement>('#list-panel')!
  panel.replaceChildren(el('h2', undefined, 'Accessible code list'), el('p', 'muted', `${visibleNodes().length} matching node${visibleNodes().length === 1 ? '' : 's'}`))
  const list = el('div', 'node-list')
  for (const node of visibleNodes().slice(0, 200)) {
    const item = button('', () => { selectedNode = node; renderInspector(); graph?.$id(node.id).select(); graph?.animate({ center: { eles: graph.$id(node.id) }, duration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 180 }); highlight() }, 'node-row')
    item.append(el('span', 'badge', node.kind), el('span', 'node-name', node.qualified_name || node.name), el('span', 'path', node.file)); list.append(item)
  }
  if (visibleNodes().length > 200) list.append(el('p', 'muted', 'Showing the first 200 matching nodes. Narrow the search to inspect more.'))
  panel.append(list)
}

function subscribe() {
  streamAbort?.abort(); if (!state) return
  const epoch = state.daemon_epoch; const after = lastDeliveredCursor || state.cursor
  const controller = new AbortController(); streamAbort = controller
  void (async () => {
    try {
      const response = await fetch(`/events?after=${encodeURIComponent(after)}`, { credentials: 'same-origin', headers: { 'X-GraphHarness-Epoch': epoch }, signal: controller.signal })
      if (response.status === 409) { await refreshState(true, true); return }
      if (!response.ok || !response.body) throw new Error(`Activity stream unavailable (${response.status})`)
      setConnection('Live observer', 'ok')
      const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
      while (true) {
        const next = await reader.read(); if (next.done) break
        buffer += decoder.decode(next.value, { stream: true }); const frames = buffer.split('\n\n'); buffer = frames.pop() || ''
        for (const frame of frames) consumeSse(frame)
      }
      if (streamAbort === controller && !controller.signal.aborted) window.setTimeout(() => {
        if (streamAbort === controller && !controller.signal.aborted) subscribe()
      }, 1000)
    } catch (error) { if (streamAbort === controller && !controller.signal.aborted) { setConnection('Reconnecting…', 'warn'); window.setTimeout(() => {
      if (streamAbort === controller && !controller.signal.aborted) void refreshState(true)
    }, 1500) } }
  })()
}

function consumeSse(frame: string) {
  const eventName = frame.split('\n').find(line => line.startsWith('event:'))?.slice(6).trim() || 'message'
  const raw = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n')
  if (!raw) return
  try {
    const payload = JSON.parse(raw) as Event & { reason?: string; cursor?: string }
    if (eventName === 'reset') { void refreshState(true, true); return }
    if (!state) return
    if (payload.daemon_epoch !== state.daemon_epoch) { void refreshState(true, true); return }
    if (activities.some(item => item.event_id === payload.event_id)) return
    activities.unshift(payload); if (activities.length > 60) activities.pop(); lastDeliveredCursor = payload.event_id
    if (['snapshot_updated', 'indexing_failed', 'session_connected', 'session_disconnected', 'session_expired', 'lease_acquired', 'lease_denied', 'lease_renewed', 'lease_released', 'lease_expired', 'edit_planned', 'edit_applied', 'edit_rejected'].includes(payload.event_type)) void reconcileState()
    renderActivity(); highlight()
  } catch { setConnection('Stream data error', 'warn') }
}

async function reconcileState() {
  if (reconciling) { reconcileAgain = true; return }
  reconciling = true
  try {
    do {
      reconcileAgain = false
      const fresh = await api<State>('/state', { method: 'GET' })
      if (!state || fresh.daemon_epoch !== state.daemon_epoch) { await refreshState(true, true); return }
      const snapshotChanged = fresh.snapshot.snapshot_id !== state.snapshot.snapshot_id
      state = fresh
      document.querySelector('.repository')!.textContent = fresh.repository_name
      document.querySelector<HTMLElement>('#workspace')!.dataset.snapshotId = fresh.snapshot.snapshot_id
      syncLanguageOptions(); renderGraph(); renderActivity(); renderList(); renderStatus(); startLeaseTicker()
      if (snapshotChanged) requestAnimationFrame(fitGraph)
      if (selectedNode) {
        selectedNode = fresh.snapshot.nodes.find(node => node.id === selectedNode!.id) || null
        renderInspector()
      }
    } while (reconcileAgain)
  } catch (error) {
    if (error instanceof ApiError && [401, 403, 409].includes(error.status)) resetObserver()
    else setConnection('Reconnecting…', 'warn')
  } finally { reconciling = false }
}

bootstrap()
void checkExistingObserver()
