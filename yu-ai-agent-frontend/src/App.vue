<script setup>
</script>

<template>
  <router-view />
</template>

<style>
:root {
  --nju-navy: #0a2540;
  --nju-navy-2: #12305c;
  --nju-blue: #1b3a70;
  --nju-blue-2: #2c5aa0;
  --nju-accent: #7bb2ff;
  --nju-gold: #c9a86a;
  --nju-bg: #f2f5fb;
  --nju-surface: #ffffff;
  --nju-surface-soft: #f7f9fd;
  --nju-border: rgba(20, 45, 90, 0.08);
  --nju-border-strong: rgba(20, 45, 90, 0.16);
  --nju-text: #0b1a33;
  --nju-text-soft: #4a5878;
  --nju-text-muted: #7c88a3;
  --shadow-sm: 0 1px 2px rgba(15, 30, 60, 0.04), 0 2px 8px rgba(15, 30, 60, 0.05);
  --shadow-md: 0 6px 20px -6px rgba(15, 30, 60, 0.15), 0 2px 6px rgba(15, 30, 60, 0.05);
  --shadow-lg: 0 24px 60px -18px rgba(10, 37, 64, 0.28), 0 8px 24px -10px rgba(10, 37, 64, 0.18);
}

* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body {
  font-family: 'Inter', 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
  font-size: 15px;
  color: var(--nju-text);
  background:
    radial-gradient(1200px 700px at 90% -10%, rgba(123, 178, 255, 0.18), transparent 60%),
    radial-gradient(900px 600px at -10% 110%, rgba(201, 168, 106, 0.10), transparent 60%),
    linear-gradient(180deg, #eef2fa 0%, #f4f7fc 100%);
  height: 100vh;
  width: 100%;
  overflow: hidden;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  letter-spacing: 0.01em;
}

#app {
  width: 100%;
  height: 100vh;
  overflow: hidden;
}

a {
  text-decoration: none;
  color: inherit;
}

button {
  cursor: pointer;
  font-family: inherit;
}

@media (max-width: 768px) {
  html, body { font-size: 14.5px; }
}
@media (max-width: 480px) {
  html, body { font-size: 14px; }
}

/* 全局细滚动条 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
::-webkit-scrollbar-track {
  background: transparent;
}
::-webkit-scrollbar-thumb {
  background: rgba(20, 45, 90, 0.18);
  border-radius: 999px;
  border: 2px solid transparent;
  background-clip: padding-box;
}
::-webkit-scrollbar-thumb:hover {
  background: rgba(20, 45, 90, 0.32);
  background-clip: padding-box;
  border: 2px solid transparent;
}

/* 打字机光标动画 */
@keyframes njuCursorBlink {
  0%, 45% { opacity: 1; }
  50%, 100% { opacity: 0; }
}

/* 进入动画 */
@keyframes njuFadeUp {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
