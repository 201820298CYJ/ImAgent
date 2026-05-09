import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue'),
    meta: {
      title: '首页-南大信管AI智能体应用平台',
      description: '南大信管AI超级智能体应用平台提供AI恋爱大师和AI超级智能体服务，满足您的各种AI对话需求'
    }
  },
  {
    path: '/nju-im-assistant',
    name: 'NjuImAssistant',
    component: () => import('../views/NjuImAssistant.vue'),
    meta: {
      title: '南京大学信息管理学院AI助手',
      description: '南京大学信息管理学院AI助手，提供信息管理领域学术辅导、院系资讯与智能问答服务'
    }
  },
  {
    path: '/super-agent',
    name: 'SuperAgent',
    component: () => import('../views/SuperAgent.vue'),
    meta: {
      title: 'AI超级智能体 - 信管AI超级智能体应用平台',
      description: 'AI超级智能体是南大信管AI超级智能体应用平台的全能助手，能解答各类专业问题，提供精准建议和解决方案'
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局导航守卫，设置文档标题
router.beforeEach((to, from, next) => {
  // 设置页面标题
  if (to.meta.title) {
    document.title = to.meta.title
  }
  next()
})

export default router 