<template>
<div class="page-container">
  <div class="table-card">
    <div class="card-header">
      <h2 class="card-title">溯源档案</h2>
      <p class="card-subtitle">与本账号相关的批次（管理员可见全部）；列表只读数据库，详情再读链</p>
    </div>
    <el-table :data="list" border stripe v-loading="loading" class="apple-table" empty-text="暂无批次">
      <el-table-column label="溯源号" prop="traceNumber" min-width="170">
        <template slot-scope="scope">
          <div class="trace-number-cell">
            <i class="el-icon-document-copy copy-icon" @click="copyData(scope.row.traceNumber)"></i>
            <span>{{ scope.row.traceNumber }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="农产品" prop="productName"></el-table-column>
      <el-table-column label="生产商">
        <template slot-scope="scope">{{ party(scope.row.producer) }}</template>
      </el-table-column>
      <el-table-column label="分销商">
        <template slot-scope="scope">{{ party(scope.row.distributor) }}</template>
      </el-table-column>
      <el-table-column label="零售商">
        <template slot-scope="scope">{{ party(scope.row.retailer) }}</template>
      </el-table-column>
      <el-table-column label="进度" width="120" align="center">
        <template slot-scope="scope">{{ progress(scope.row) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260" align="center">
        <template slot-scope="scope">
          <el-button type="primary" size="small" class="action-button"
            @click="$router.push('/qrcodeTrace/' + encodeURIComponent(scope.row.traceNumber))">
            二维码
          </el-button>
          <el-button type="success" size="small" class="action-button"
            @click="$router.push('/batch/' + encodeURIComponent(scope.row.traceNumber))">
            批次详情
          </el-button>
          <el-button size="small" class="action-button"
            @click="$router.push('/traceDetail/' + encodeURIComponent(scope.row.traceNumber))">
            消费者视图
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</div>
</template>

<script>
import { listBatches } from "@/apis/trace"

export default {
  name: "TraceList",
  data() {
    return {
      list: [],
      loading: false
    }
  },
  created() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      const res = await listBatches()
      this.loading = false
      if (res.code === 200) {
        this.list = res.data
      } else {
        this.list = []
        this.$message.error(res.mes)
      }
    },
    party(p) {
      return p ? (p.companyName || p.username) : '未指定'
    },
    progress(row) {
      const done = ['PRODUCTION', 'DISTRIBUTION', 'RETAIL'].filter(k => row.stages[k].status === 'CONFIRMED').length
      return `已上链 ${done} / 3`
    }
  }
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

.table-card {
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
  width: 100%;
  max-width: 1200px;
}

.table-card {
  width: 90%;
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

.apple-table {
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid rgba(0, 0, 0, 0.1);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.05);
}

:deep(.apple-table th) {
  background-color: #f5f7fa !important;
  color: #333 !important;
  font-weight: 600;
  padding: 14px 0;
  border-color: rgba(0, 0, 0, 0.1) !important;
}

:deep(.apple-table td) {
  padding: 12px 0;
  border-color: rgba(0, 0, 0, 0.05) !important;
}

:deep(.apple-table .el-table__row) {
  transition: background-color 0.2s ease;
}

:deep(.apple-table .el-table__row:hover) {
  background-color: rgba(0, 0, 0, 0.02);
}

.trace-number-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
}

.copy-icon {
  cursor: pointer;
  color: #409EFF;
  font-size: 16px;
  transition: color 0.3s ease;

  &:hover {
    color: #66b1ff;
  }
}

.action-button {
  margin: 0 4px;
  border-radius: 8px;
  font-weight: 500;
}

@media (max-width: 768px) {
  .page-container {
    padding: 20px;
  }

  .table-card {
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