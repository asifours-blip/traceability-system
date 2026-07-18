<template>
<div class="page-container">
  <div class="form-card">
    <div class="card-header">
      <h2 class="card-title">溯源二维码</h2>
      <p class="card-subtitle">扫描二维码查看溯源详情</p>
    </div>
    <div class="qrcode-container" ref="qrcodeContainer"></div>
  </div>
</div>
</template>

<script>
import QRCode from 'qrcode'
export default {
  name: 'QrcodeTrace',
  mounted() {
    this.generateQRCode()
  },
  methods: {
    generateQRCode() {
      const content = window.location.origin + "/traceDetail/" + this.$route.params.traceNumber
      const container = this.$refs.qrcodeContainer

      QRCode.toCanvas(content, { width: 200 }, (err, canvas) => {
        if (err) {
          console.error('生成二维码失败:', err)
          return
        }
        container.appendChild(canvas)
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.page-container {
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
  padding: 30px;
  display: flex;
  flex-direction: column;
  gap: 30px;
  align-items: center;
  min-height: 100vh;
}

.form-card {
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
  width: 100%;
  max-width: 960px;
}

.card-header {
  text-align: center;
  margin-bottom: 30px;
}

.card-title {
  font-size: 28px;
  font-weight: 600;
  color: #1d1d1f;
  margin: 0;
  letter-spacing: -0.5px;
}

.card-subtitle {
  font-size: 16px;
  color: #86868b;
  margin-top: 8px;
  font-weight: 400;
}

.qrcode-container {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 20px;
  background: rgba(249, 249, 249, 0.6);
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

@media (max-width: 768px) {
  .page-container {
    padding: 20px;
  }

  .form-card {
    padding: 30px 20px;
  }

  .card-title {
    font-size: 24px;
  }

  .card-subtitle {
    font-size: 14px;
  }
}
</style>
