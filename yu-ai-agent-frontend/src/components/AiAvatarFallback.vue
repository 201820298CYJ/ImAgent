<template>
  <div class="ai-avatar-fallback" :class="type">
    <img v-if="type !== 'user'" :src="logo" alt="信管" class="logo-img" />
    <span v-else class="user-glyph">我</span>
  </div>
</template>

<script setup>
defineProps({
  type: {
    type: String,
    default: 'default'
  }
})
const logo = new URL('../assets/nju-im-logo.png', import.meta.url).href
</script>

<style scoped>
.ai-avatar-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #fff;
  overflow: hidden;
  position: relative;
  isolation: isolate;
}

.ai-avatar-fallback::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.35);
  pointer-events: none;
}

.default, .super {
  background:
    radial-gradient(120% 120% at 20% 15%, #4b7fd6 0%, #1b3a70 55%, #0a2540 100%);
}

.love {
  background: linear-gradient(135deg, #ff6b8b, #ff8e8e);
}

.user {
  background: linear-gradient(135deg, #eef2fa 0%, #d9e2f2 100%);
  color: var(--nju-navy);
}

.logo-img {
  width: 72%;
  height: 72%;
  object-fit: contain;
  filter: brightness(0) invert(1);
  opacity: 0.95;
}

.user-glyph {
  font-weight: 600;
  font-size: 14px;
  color: var(--nju-navy);
  letter-spacing: 0.02em;
}
</style>
