import { defineStore } from 'pinia'
import http from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: localStorage.getItem('at') || '',
    refreshToken: localStorage.getItem('rt') || '',
    userId: localStorage.getItem('uid') || '',
    username: localStorage.getItem('uname') || '',
    roles: JSON.parse(localStorage.getItem('roles') || '[]')
  }),
  getters: {
    isLoggedIn: s => !!s.accessToken,
    isAdmin: s => s.roles.includes('ADMIN')
  },
  actions: {
    setLogin(data) {
      this.accessToken = data.accessToken
      this.refreshToken = data.refreshToken
      this.userId = data.userId
      this.username = data.username
      this.roles = data.roles || []
      localStorage.setItem('at', data.accessToken)
      localStorage.setItem('rt', data.refreshToken)
      localStorage.setItem('uid', data.userId)
      localStorage.setItem('uname', data.username)
      localStorage.setItem('roles', JSON.stringify(this.roles))
    },
    async doRefresh() {
      const resp = await http.post('/api/auth/refresh', { refreshToken: this.refreshToken })
      this.setLogin(resp.data.data)
    },
    async logout() {
      try {
        await http.post('/api/auth/logout', { refreshToken: this.refreshToken })
      } catch { /* best effort */ }
      this.clear()
    },
    clear() {
      this.accessToken = this.refreshToken = ''
      this.userId = this.username = ''
      this.roles = []
      ;['at','rt','uid','uname','roles'].forEach(k => localStorage.removeItem(k))
    }
  }
})
