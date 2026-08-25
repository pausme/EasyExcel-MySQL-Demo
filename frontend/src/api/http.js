import axios from 'axios'
import { useAuthStore } from '../stores/auth'
import router from '../router'

const http = axios.create({ timeout: 30000 })

http.interceptors.request.use(config => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

let refreshing = null
http.interceptors.response.use(
  resp => {
    const b = resp.data
    if (b && typeof b === 'object' && b.success === false) {
      return Promise.reject(new Error(b.message || '请求失败'))
    }
    return resp
  },
  async err => {
    const auth = useAuthStore()
    const status = err.response?.status
    // FE-03 修复：refresh 自身 401 时不再重试，直接登出
    const isRefreshCall = err.config?.url?.includes('/api/auth/refresh')
    if (status === 401 && auth.refreshToken && !err.config._retried && !isRefreshCall) {
      err.config._retried = true
      try {
        refreshing = refreshing || auth.doRefresh()
        await refreshing
        refreshing = null
        return http(err.config)
      } catch {
        refreshing = null
        auth.clear()
        router.push('/login')
        return Promise.reject(new Error('登录已过期，请重新登录'))
      }
    }
    if (status === 401 && isRefreshCall) {
      auth.clear()
      router.push('/login')
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }
    const msg = err.response?.data?.message || err.message || '网络异常'
    return Promise.reject(new Error(msg))
  }
)

/** 后端统一响应体 {success,code,message,data} → 解包 data */
export async function getData(url, config) {
  const resp = await http.get(url, config)
  return resp.data?.data
}
export async function postData(url, body, config) {
  const resp = await http.post(url, body, config)
  return resp.data?.data
}
export default http
