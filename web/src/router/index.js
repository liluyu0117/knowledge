import { createRouter, createWebHistory } from 'vue-router'
import AskView from '../views/AskView.vue'
import SettingsView from '../views/SettingsView.vue'
import RetrieveTestView from '../views/RetrieveTestView.vue'
import KbManageView from '../views/KbManageView.vue'
import NotesView from '../views/NotesView.vue'

const routes = [
  { path: '/', name: 'ask', component: AskView },
  { path: '/kb', name: 'kb', component: KbManageView },
  { path: '/notes', name: 'notes', component: NotesView },
  { path: '/settings', name: 'settings', component: SettingsView },
  { path: '/retrievetest', name: 'retrievetest', component: RetrieveTestView },
]

export default createRouter({ history: createWebHistory(), routes })
