import { createRouter, createWebHashHistory, type RouterHistory } from 'vue-router'

// App owns the small workspace shell; routes select its current panel.
export function makeRouter(history: RouterHistory = createWebHashHistory()) {
  return createRouter({ history, routes: [
    { path: '/', redirect: '/conversations' },
    ...['login', 'password', 'conversations', 'admin'].map(name => ({ path: `/${name}`, name, component: {} })),
    { path: '/conversations/:id', name: 'conversation', component: {} },
    { path: '/:pathMatch(.*)*', redirect: '/conversations' },
  ] })
}
