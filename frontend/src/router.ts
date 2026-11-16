import { createRouter, createWebHashHistory, type RouterHistory } from 'vue-router'

// App owns the small workspace shell; routes select its current panel.
export function makeRouter(history: RouterHistory = createWebHashHistory()) {
  return createRouter({ history, routes: [
    { path: '/', redirect: '/conversations' },
    ...['projects', 'agents', 'login', 'password', 'conversations', 'admin', 'models', 'admin-models'].map(name => ({ path: `/${name}`, name, component: {} })),
    { path: '/projects/:id', name: 'project', component: {} },
    { path: '/conversations/:id', name: 'conversation', component: {} },
    { path: '/:pathMatch(.*)*', redirect: '/conversations' },
  ] })
}
