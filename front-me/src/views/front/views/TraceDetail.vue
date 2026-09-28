<template>
  <div class="page-container">
    <div class="detail-card" v-loading="loading">
      <h2 class="card-title">农产品溯源详情</h2>
      <p class="card-subtitle">溯源号 {{ traceNumber }} · 以下为链上原始记录；「链下更正」为写入者事后追加的说明，链上记录本身不会被修改</p>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"></el-alert>

      <el-timeline v-if="detail" class="apple-timeline">
        <template v-for="stage in stages">
          <el-timeline-item v-if="recorded(stage)" :key="stage.key"
            :timestamp="'上链时间 ' + formatTime(detail[stage.legacy].timestamp)" placement="top">
            <el-card class="timeline-card">
              <h4 class="stage-title">{{ stage.label }}阶段</h4>
              <div class="info-grid">
                <div v-for="f in publicFields(stage)" :key="f.name" class="info-item">
                  <strong>{{ f.label }}：</strong>
                  <span class="value-text">
                    {{ detail[stage.legacy][f.name] }}
                    <el-tooltip v-for="c in correctionsOf(stage, f.name)" :key="c.id + f.name" placement="top"
                      :content="'原因：' + c.reason + '（' + (c.authorCompany || '写入者') + '，' + formatTime(c.createdAt) + '）'">
                      <el-tag size="mini" type="warning" class="corr-tag">链下更正：{{ c.value }}</el-tag>
                    </el-tooltip>
                  </span>
                </div>
                <div v-if="stage.file && detail[stage.legacy].hasFile" class="info-item">
                  <strong>{{ stage.fileLabel }}：</strong>
                  <template v-if="detail[stage.legacy].fileState === 'AVAILABLE'">
                    <el-image v-if="(detail[stage.legacy].fileType || '').startsWith('image/')" :src="fileUrl(stage)"
                      :preview-src-list="[fileUrl(stage)]" fit="contain" class="thumb">
                      <div slot="error" class="thumb-error">文件读取失败</div>
                    </el-image>
                    <a v-else :href="fileUrl(stage)" target="_blank" rel="noopener">下载{{ stage.fileLabel }}</a>
                  </template>
                  <span v-else class="file-note">{{ fileStateText[detail[stage.legacy].fileState] || '文件暂不可用' }}</span>
                </div>
              </div>
              <div v-if="proofOf(stage).txHash" class="proof">
                交易哈希 <code>{{ proofOf(stage).txHash }}</code>
                <span v-if="proofOf(stage).blockNumber"> · 块高 {{ proofOf(stage).blockNumber }}</span>
              </div>
            </el-card>
          </el-timeline-item>
        </template>
      </el-timeline>

      <!-- 物联网监测区块：IoT 接口需要登录，消费者（未登录）不展示 -->
      <el-card v-if="traceNumber && detail && loggedIn" class="iot-card">
        <div slot="header">
          <span>生长环境监测（物联网数据，定时模拟任务生成，非真实传感器）</span>
        </div>
        <IotChart :batchId="traceNumber" />
      </el-card>
    </div>
  </div>
</template>

<script>
import IotChart from "@/components/IotChart.vue";
import { getTraceDetail, publicFileUrl } from "@/apis/trace"
import { STAGES, STAGE_FIELDS, formatTime } from "@/utils/traceFields"
import { getToken } from "@/utils/auth"

// 消费者扫码页（免登录）：后端只返回公开字段，这里也只按公开字段清单展示
export default {
  name: "trace-detail",
  components: {
    IotChart,
  },
  data() {
    return {
      stages: STAGES,
      detail: null,
      loading: false,
      error: '',
      loggedIn: !!getToken(),
      // 后端 fileState：AVAILABLE 可读；其余给出明确说明，不显示空图片
      fileStateText: {
        MISSING: '文件缺失：链上登记了该文件，但存储节点上已找不到内容',
        NOT_BOUND: '文件尚未绑定（对应交易未确认或文件未登记），暂不公开',
        UNAVAILABLE: '文件存储服务暂时不可用，请稍后再试'
      }
    };
  },
  computed: {
    traceNumber() {
      return this.$route.params.traceNumber;
    },
  },
  watch: {
    traceNumber() {
      this.load()
    }
  },
  created() {
    this.load()
  },
  methods: {
    formatTime,
    async load() {
      this.loading = true
      const res = await getTraceDetail(this.traceNumber);
      this.loading = false
      if (res.code === 200) {
        this.detail = res.data
        this.error = ''
      } else {
        this.detail = null
        this.error = res.code === 404 ? '未找到该溯源号的链上记录' : res.mes
      }
    },
    recorded(stage) {
      const d = this.detail && this.detail[stage.legacy]
      return d && Object.keys(d).length > 0
    },
    publicFields(stage) {
      return STAGE_FIELDS[stage.key].filter(f => f.public)
    },
    proofOf(stage) {
      return (this.detail.stages || []).find(s => s.stage === stage.key) || {}
    },
    // 某字段的全部链下更正（按提交顺序）
    correctionsOf(stage, field) {
      const list = []
      ;(this.proofOf(stage).corrections || []).forEach(c => {
        c.fields.filter(f => f.field === field).forEach(f => list.push({ ...c, value: f.value }))
      })
      return list
    },
    fileUrl(stage) {
      return publicFileUrl(this.traceNumber, stage.file)
    }
  },
};
</script>

<style lang="scss" scoped>
.page-container {
  /* Adjust based on your header/footer height */
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
  padding: 30px;
  display: flex;
  flex-direction: column;
  gap: 30px;
  align-items: center;
}

.detail-card {
  box-sizing: border-box;
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
}

.card-title {
  font-size: 28px;
  font-weight: 600;
  color: #1d1d1f;
  margin-bottom: 8px;
  text-align: center;
  letter-spacing: -0.5px;
}

.card-subtitle {
  font-size: 16px;
  color: #86868b;
  margin-bottom: 30px;
  text-align: center;
  font-weight: 400;
}

.apple-timeline {
  margin-top: 30px;
}

:deep(.el-timeline-item__tail) {
  border-left: 2px solid #d2d2d7;
}

:deep(.el-timeline-item__node) {
  background-color: #0071e3 !important;
  border: none;
  width: 12px;
  height: 12px;
  left: -2px;
}

:deep(.el-timeline-item__timestamp) {
  color: #86868b;
  font-size: 14px;
  margin-top: 5px;
}

.timeline-card {
  background: rgba(255, 255, 255, 0.6) !important;
  border-radius: 12px !important;
  border: 1px solid rgba(255, 255, 255, 0.4) !important;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  padding: 25px !important;
}

.stage-title {
  font-size: 20px;
  font-weight: 600;
  color: #333;
  margin-bottom: 20px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 15px 20px;
}

.info-item {
  font-size: 15px;
  color: #555;
  display: flex;
  align-items: center;
  word-break: break-word;
}

.info-item strong {
  color: #333;
  font-weight: 500;
  margin-right: 8px;
  flex-shrink: 0;
}

.value-text {
  flex-grow: 1;
}

.preview-btn {
  color: #0071e3;
  font-size: 15px;
  padding: 0;
  height: auto;
  min-height: auto;
}

.preview-btn:hover {
  text-decoration: underline;
}
.corr-tag {
  margin-left: 6px;
  cursor: help;
}

.thumb {
  width: 120px;
  height: 90px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}

.file-note {
  color: #f56c6c;
}

.thumb-error {
  font-size: 12px;
  color: #86868b;
  padding: 30px 8px;
}

.proof {
  margin-top: 14px;
  font-size: 12px;
  color: #86868b;
  word-break: break-all;
}

.iot-card {
  margin-top: 30px;
}

.iot-note {
  margin-top: 10px;
  color: #999;
  font-size: 12px;
  text-align: center;
}

@media (max-width: 768px) {
  .detail-card {
    padding: 30px 20px;
  }

  .card-title {
    font-size: 24px;
  }

  .card-subtitle {
    font-size: 14px;
  }

  .stage-title {
    font-size: 18px;
  }

  .info-item {
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .info-grid {
    grid-template-columns: 1fr;
  }
}
</style>