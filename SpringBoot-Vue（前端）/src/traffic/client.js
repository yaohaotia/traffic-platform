import axios from 'axios'
const KEY = 'traffic-platform-session-v1'
let pair = null
try { pair = JSON.parse(sessionStorage.getItem(KEY) || 'null') } catch (_) { sessionStorage.removeItem(KEY) }
let refreshing = null
const transport = axios.create({ baseURL: process.env.VUE_APP_API_BASE || '/api/v1', timeout: 20000 })
function save(value) { pair = value; if (value) sessionStorage.setItem(KEY, JSON.stringify(value)); else sessionStorage.removeItem(KEY) }
function expired() { save(null); window.dispatchEvent(new Event('traffic-session-expired')) }
transport.interceptors.request.use(config => { if (pair) config.headers.Authorization = `Bearer ${pair.accessToken}`; return config })
transport.interceptors.response.use(response => response, async error => {
  const config = error.config
  if (error.response && error.response.status === 401 && config && !config.trafficRetried && pair) {
    config.trafficRetried = true
    try {
      if (!refreshing) refreshing = axios.post(`${transport.defaults.baseURL}/auth/refresh`, { refreshToken: pair.refreshToken }, { timeout: 10000 }).then(response => save(response.data)).finally(() => { refreshing = null })
      await refreshing
      config.headers.Authorization = `Bearer ${pair.accessToken}`
      return transport.request(config)
    } catch (failure) { expired(); return Promise.reject(failure) }
  }
  if (error.response && error.response.status === 401) expired()
  return Promise.reject(error)
})
export const api = {
  hasSession: () => !!pair,
  async login(username, password) { const result = await axios.post(`${transport.defaults.baseURL}/auth/login`, { username, password }, { timeout: 10000 }); save(result.data); return this.get('/auth/me') },
  async logout() { try { await transport.post('/auth/logout') } finally { save(null) } },
  get: (url, params) => transport.get(url, { params }).then(r => r.data),
  post: (url, data, key) => transport.post(url, data, key ? { headers: { 'Idempotency-Key': key } } : {}).then(r => r.data),
  put: (url, data) => transport.put(url, data).then(r => r.data),
  async upload(file) { const form = new FormData(); form.append('image', file); return (await transport.post('/evidence', form)).data },
  async image(url) { return URL.createObjectURL((await transport.get(url, { responseType: 'blob' })).data) },
  async specification() { return (await transport.get('/v3/api-docs', { baseURL: '' })).data }
}
export function requestKey() {
  const bytes = new Uint8Array(16); window.crypto.getRandomValues(bytes)
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
}
export function message(error) { return error.response && error.response.data && error.response.data.message || (error.response ? `请求失败（${error.response.status}）` : error.request ? '服务暂时无法连接，请稍后重试' : error.message || '操作未完成') }
