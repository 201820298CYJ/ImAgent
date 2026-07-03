<template>
  <footer class="app-footer">
    <div class="footer-inner">
      <div class="footer-brand">
        <img :src="logo" alt="南京大学信息管理学院" class="footer-logo" />
        <div class="brand-text">
          <div class="brand-line-1">南京大学 · 信息管理学院</div>
          <div class="brand-line-2">School of Information Management, Nanjing University</div>
        </div>
      </div>
      <div class="footer-meta">
        <span>© {{ currentYear }} NJU-SIM · AI Agent</span>
        <span class="dot">·</span>
        <span>Design By Joey Chen</span>
      </div>
    </div>
  </footer>
</template>

<script setup>
import { computed } from 'vue'
const currentYear = computed(() => new Date().getFullYear())
const logo = new URL('../assets/nju-im-logo-icon.png', import.meta.url).href
</script>

<style scoped>
.app-footer {
  width: 100%;
  padding: 14px 32px 16px;
  background: transparent;
  flex: 0 0 auto;
}

.footer-inner {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.footer-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.footer-logo {
  height: 30px;
  width: 30px;
  object-fit: contain;
  opacity: 0.9;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.brand-line-1 {
  font-size: 14px;
  font-weight: 600;
  color: var(--nju-navy);
  letter-spacing: 0.05em;
}

.brand-line-2 {
  font-size: 11px;
  color: var(--nju-text-muted);
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.footer-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--nju-text-muted);
  font-size: 12px;
  letter-spacing: 0.03em;
}

.dot {
  opacity: 0.5;
}

@media (max-width: 640px) {
  .footer-inner {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .app-footer { padding: 10px 16px 14px; }
}
</style>
