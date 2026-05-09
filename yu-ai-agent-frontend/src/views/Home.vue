<template>
  <div class="njuim-home-container">
    <header class="njuim-header">
      <img class="njuim-logo" :src="logo" alt="南京大学信息管理学院徽章" />
      <span class="njuim-title-simple">南京大学信息管理学院AI智能体 
<span class="njuim-title-emoji" style="font-size: 1.5em;">🤖</span></span><!-- <span class="njuim-title-simple">南京大学信息管理学院AI智能体 <span class="njuim-title-emoji">🤖</span></span> -->
    </header>
    <main class="njuim-main">
      <div class="njuim-apps">
        <div class="njuim-app-card" @click="navigateTo('/nju-im-assistant')">
          <div class="njuim-app-emoji">🎓</div>
          <div class="njuim-app-title">信管 AI 助手</div>
          <div class="njuim-app-desc">院系资讯·学术辅导·智能问答</div>
        </div>
        <div class="njuim-app-card" @click="navigateTo('/super-agent')">
          <div class="njuim-app-emoji">🤖</div>
          <div class="njuim-app-title">信管 AI 智能体</div>
          <div class="njuim-app-desc">全能AI助手·解决各类问题</div>
        </div>
      </div>
    </main>
    <AppFooter />
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import AppFooter from '../components/AppFooter.vue'

const logo = new URL('../assets/nju-im-logo.png', import.meta.url).href

useHead({
  meta: [
    {
      name: 'description',
      content: '南京大学信息管理学院智能体，提供院系资讯、学术辅导、智能问答等服务'
    },
    {
      name: 'keywords',
      content: '南京大学,信息管理学院,AI助手,智能问答,学术辅导,院系资讯'
    }
  ]
})

const router = useRouter()
const navigateTo = (path) => {
  router.push(path)
}
</script>

<style scoped>
.njuim-home-container {
  min-height: 100vh;
  background: #f7fafd;
  display: flex;
  flex-direction: column;
}

.njuim-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 32px 40px 16px 40px;
  background: #264b8c;
  border-bottom: 1px solid #e5eaf3;
}

.njuim-logo {
  height: 48px;
  margin-right: 18px;
}

.njuim-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 0 60px 0;
}

.njuim-apps {
  display: flex;
  gap: 48px;
  flex-wrap: wrap;
  justify-content: center;
  margin-top: 24px;
}

.njuim-app-card {
  background: #fff;
  border: 2px solid #e5eaf3;
  border-radius: 20px;
  box-shadow: 0 6px 32px 0 rgba(38,75,140,0.08), 0 1.5px 6px 0 rgba(0,0,0,0.03);
  padding: 56px 64px 44px 64px;
  min-width: 340px;
  max-width: 420px;
  cursor: pointer;
  transition: box-shadow 0.25s, border-color 0.25s, transform 0.18s;
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
  overflow: visible;
}
.njuim-app-card:hover {
  border-color: #005bac;
  box-shadow: 0 12px 48px 0 rgba(0,91,172,0.13), 0 2px 8px 0 rgba(0,0,0,0.04);
  transform: translateY(-6px) scale(1.035);
}
.njuim-app-title {
  font-size: 2rem;
  font-weight: 700;
  color: #1760a8;
  margin-bottom: 18px;
  letter-spacing: 1.5px;
  text-align: center;
  background: linear-gradient(90deg, #005bac 30%, #4f8cff 70%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  text-fill-color: transparent;
}
.njuim-app-desc {
  font-size: 1.18rem;
  color: #444;
  text-align: center;
  letter-spacing: 1px;
}

.njuim-app-emoji {
  font-size: 3.2rem;
  margin-bottom: 18px;
  filter: drop-shadow(0 2px 8px #e5eaf3);
  user-select: none;
  transition: transform 0.18s;
}
.njuim-app-card:hover .njuim-app-emoji {
  transform: scale(1.12) rotate(-8deg);
}

@media (max-width: 900px) {
  .njuim-apps {
    flex-direction: column;
    align-items: center;
    gap: 32px;
  }
}

@media (max-width: 768px) {
  .njuim-header {
    padding: 24px 0 12px 16px;
  }
  .njuim-main {
    padding: 24px 0 40px 0;
  }
  .njuim-app-card {
    padding: 32px 12px 28px 12px;
    min-width: 90vw;
    max-width: 98vw;
  }
  .njuim-app-title {
    font-size: 1.3rem;
  }
  .njuim-app-desc {
    font-size: 1rem;
  }
}

@import url('https://fonts.googleapis.com/css2?family=Orbitron:wght@700&display=swap');
.njuim-title-simple {
  text-align: right;
  font-size: 2rem;
  font-weight: 700;
  color: #fff;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  letter-spacing: 2px;
  flex: 1;
  display: block;
  line-height: 1.2;
}
.njuim-title-emoji {
  font-size: 1.7rem;
  margin-left: 10px;
  vertical-align: middle;
}
</style> 