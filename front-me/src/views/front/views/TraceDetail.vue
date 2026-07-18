<template>
  <div class="page-container">
    <div class="detail-card">
      <h2 class="card-title">农产品溯源详情</h2>
      <p class="card-subtitle">追溯农产品的生产、分销与零售全过程</p>

      <el-timeline class="apple-timeline">
        <!-- 生产阶段 -->
        <el-timeline-item
            v-if="detail.producer"
            :timestamp="formatTime(detail.producer.timestamp)"
            placement="top"
        >
          <el-card class="timeline-card">
            <h4 class="stage-title">生产阶段</h4>
            <div class="info-grid">
              <div class="info-item">
                <strong>溯源码：</strong
                ><span class="value-text">{{ detail.producer.traceNumber }}</span>
              </div>
              <div class="info-item">
                <strong>公司名称：</strong
                ><span class="value-text">{{ detail.producer.companyName }}</span>
              </div>
              <div class="info-item">
                <strong>产品名称：</strong
                ><span class="value-text">{{ detail.producer.productName }}</span>
              </div>
              <div class="info-item">
                <strong>生产地点：</strong
                ><span class="value-text">{{ detail.producer.productionLocation }}</span>
              </div>
              <div class="info-item">
                <strong>品种：</strong><span class="value-text">{{ detail.producer.variety }}</span>
              </div>
              <div class="info-item">
                <strong>生产批次：</strong
                ><span class="value-text">{{ detail.producer.productionBatch }}</span>
              </div>
              <div class="info-item">
                <strong>生产认证：</strong
                ><el-button
                  type="text"
                  class="preview-btn"
                  @click="$store.commit('showImg', detail.producer.productionCert)"
              >预览</el-button
              >
              </div>
              <div class="info-item">
                <strong>生产时间：</strong
                ><span class="value-text">{{ detail.producer.productTime }}</span>
              </div>
            </div>
          </el-card>
        </el-timeline-item>

        <!-- 分销阶段 -->
        <el-timeline-item
            v-if="Object.keys(detail.distributor).length > 0"
            :timestamp="formatTime(detail.distributor.timestamp)"
            placement="top"
        >
          <el-card class="timeline-card">
            <h4 class="stage-title">分销阶段</h4>
            <div class="info-grid">
              <div class="info-item">
                <strong>溯源码：</strong
                ><span class="value-text">{{ detail.distributor.traceNumber }}</span>
              </div>
              <div class="info-item">
                <strong>公司名称：</strong
                ><span class="value-text">{{ detail.distributor.companyName }}</span>
              </div>
              <div class="info-item">
                <strong>存储条件：</strong
                ><span class="value-text">{{ detail.distributor.storageCondition }}</span>
              </div>
              <div class="info-item">
                <strong>运输方式：</strong
                ><span class="value-text">{{ detail.distributor.transportMethod }}</span>
              </div>
              <div class="info-item">
                <strong>分销批次：</strong
                ><span class="value-text">{{ detail.distributor.distributeBatch }}</span>
              </div>
              <div class="info-item">
                <strong>存储地点：</strong
                ><span class="value-text">{{ detail.distributor.storageLocation }}</span>
              </div>
              <div class="info-item">
                <strong>分销价格(w)：</strong
                ><span class="value-text">{{ detail.distributor.distributePrice }}</span>
              </div>
              <div class="info-item">
                <strong>分销数量(kg)：</strong
                ><span class="value-text">{{ detail.distributor.distributeQuantity }}</span>
              </div>
              <div class="info-item">
                <strong>检验报告：</strong
                ><el-button
                  type="text"
                  class="preview-btn"
                  @click="$store.commit('showImg', detail.distributor.inspectionReport)"
              >预览</el-button
              >
              </div>
            </div>
          </el-card>
        </el-timeline-item>

        <!-- 零售阶段 -->
        <el-timeline-item
            v-if="Object.keys(detail.retailer).length > 0"
            :timestamp="formatTime(detail.retailer.timestamp)"
            placement="top"
        >
          <el-card class="timeline-card">
            <h4 class="stage-title">零售阶段</h4>
            <div class="info-grid">
              <div class="info-item">
                <strong>溯源码：</strong
                ><span class="value-text">{{ detail.retailer.traceNumber }}</span>
              </div>
              <div class="info-item">
                <strong>公司名称：</strong
                ><span class="value-text">{{ detail.retailer.companyName }}</span>
              </div>
              <div class="info-item">
                <strong>销售价格(w)：</strong
                ><span class="value-text">{{ detail.retailer.salePrice }}</span>
              </div>
              <div class="info-item">
                <strong>销售数量(kg)：</strong
                ><span class="value-text">{{ detail.retailer.saleQuantity }}</span>
              </div>
              <div class="info-item">
                <strong>保质期(天)：</strong
                ><span class="value-text">{{ detail.retailer.shelfLife }}</span>
              </div>
              <div class="info-item">
                <strong>发票号：</strong><span class="value-text">{{ detail.retailer.invoiceNo }}</span>
              </div>
              <div class="info-item">
                <strong>销售时间：</strong
                ><span class="value-text">{{ detail.retailer.saleTime }}</span>
              </div>
            </div>
          </el-card>
        </el-timeline-item>
      </el-timeline>

      <!-- 新增：物联网监测区块 -->
      <el-card v-if="traceNumber" class="iot-card">
        <div slot="header">
          <span>🌱 生长环境监测（物联网数据）</span>
        </div>
        <IotChart :batchId="traceNumber" />
      </el-card>
    </div>
  </div>
</template>

<script>
import { dateTimeUtils } from "@/utils/commonUtil";
import IotChart from "@/components/IotChart.vue"; // 引入物联网图表组件

export default {
  name: "trace-detail",
  components: {
    IotChart, // 注册组件
  },
  data() {
    return {
      dateTimeUtils,
      detail: {},
    };
  },
  computed: {
    // 从路由参数获取溯源码，作为物联网数据的批次号
    traceNumber() {
      return this.$route.params.traceNumber;
    },
  },
  async created() {
    const { data } = await this.$http.get("/trace/detail/" + this.$route.params.traceNumber);
    this.detail = data || {};
  },
  methods: {
    formatTime(timestamp) {
      return dateTimeUtils.formatTimestamp(timestamp);
    },
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