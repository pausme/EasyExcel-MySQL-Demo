import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    children: [
      { path: '', redirect: '/ops' },
      { path: 'ops', name: 'ops', component: () => import('../views/OpsDashboard.vue'), meta: { admin: true } },
      { path: 'students', name: 'students', component: () => import('../views/Students.vue') },
      { path: 'import', name: 'import', component: () => import('../views/ImportWizard.vue') },
      { path: 'tasks', name: 'tasks', component: () => import('../views/Tasks.vue') },
      { path: 'files', name: 'files', component: () => import('../views/Files.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) return { name: 'login' }
  if (to.meta.admin && !auth.isAdmin) return { name: 'students' }
})

export default router
