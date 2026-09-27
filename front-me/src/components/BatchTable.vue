<template>
  <div class="table-card">
    <div class="card-header">
      <h2 class="card-title">{{ title }}</h2>
      <p class="card-subtitle">{{ subtitle }}</p>
    </div>
    <div class="toolbar">
      <el-radio-group v-model="filter" size="small" @change="syncQuery">
        <el-radio-button label="all">全部</el-radio-button>
        <el-radio-button label="todo">待我处理</el-radio-button>
      </el-radio-group>
      <el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="block-alert"></el-alert>
    <el-table v-loading="loading" :data="rows" border stripe class="apple-table" empty-text="暂无批次">
      <el-table-column label="溯源号" min-width="170">
        <template slot-scope="scope">
          <router-link :to="detailLink(scope.row)" class="trace-link">{{ scope.row.traceNumber }}</router-link>
        </template>
      </el-table-column>
      <el-table-column prop="productName" label="产品" min-width="90"></el-table-column>
      <el-table-column label="生产商" min-width="110">
        <template slot-scope="scope">{{ partyText(scope.row.producer) }}</template>
      </el-table-column>
      <el-table-column label="分销商" min-width="110">
        <template slot-scope="scope">{{ partyText(scope.row.distributor) }}</template>
      </el-table-column>
      <el-table-column label="零售商" min-width="110">
        <template slot-scope="scope">{{ partyText(scope.row.retailer) }}</template>
      </el-table-column>
      <el-table-column v-for="stage in stages" :key="stage.key" :label="stage.label" width="130" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="statusMeta(scope.row, stage.key).type">{{ statusMeta(scope.row, stage.key).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="center">
        <template slot-scope="scope">
          <el-button type="text" @click="$router.push(detailLink(scope.row))">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import { listBatches } from '@/apis/trace'
import { STAGES, STATUS_META } from '@/utils/traceFields'

// 各角色的批次列表：数据只来自后端 /batches（后端按账号过滤，前端不做权限判断）
export default {
  name: 'BatchTable',
  props: {
    title: { type: String, default: '我的批次' },
    subtitle: { type: String, default: '' },
    // 当前角色负责的阶段：用于「待我处理」筛选和详情页定位
    myStage: { type: String, default: 'PRODUCTION' },
    // 加 1 触发刷新（父页面提交成功后）
    reloadKey: { type: Number, default: 0 }
  },
  data() {
    return {
      stages: STAGES,
      list: [],
      loading: false,
      error: '',
      // 筛选条件放在路由 query 里，刷新后保持
      filter: this.$route.query.filter === 'todo' ? 'todo' : 'all'
    }
  },
  computed: {
    rows() {
      if (this.filter !== 'todo') {
        return this.list
      }
      return this.list.filter(row => this.isTodo(row))
    }
  },
  watch: {
    reloadKey() {
      this.load()
    }
  },
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      const res = await listBatches()
      this.loading = false
      if (res.code === 200) {
        this.list = res.data || []
        this.error = ''
      } else {
        this.list = []
        this.error = res.mes
      }
    },
    // 待我处理：我负责的阶段还没上链（含待确认、失败）
    isTodo(row) {
      const s = row.stages[this.myStage]
      if (!s || s.status === 'CONFIRMED') {
        return false
      }
      if (this.myStage === 'DISTRIBUTION') {
        return row.stages.PRODUCTION.status === 'CONFIRMED'
      }
      if (this.myStage === 'RETAIL') {
        return row.stages.DISTRIBUTION.status === 'CONFIRMED'
      }
      return true
    },
    statusMeta(row, stage) {
      const s = row.stages && row.stages[stage]
      return STATUS_META[s ? s.status : 'NOT_STARTED'] || STATUS_META.NOT_STARTED
    },
    partyText(p) {
      return p ? (p.companyName || p.username) : '未指定'
    },
    detailLink(row) {
      return { path: '/batch/' + encodeURIComponent(row.traceNumber), query: { stage: this.myStage } }
    },
    syncQuery() {
      const query = { ...this.$route.query, filter: this.filter === 'todo' ? 'todo' : undefined }
      this.$router.replace({ query }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.table-card {
  background: rgba(255, 255, 255, 0.8);
  border-radius: 20px;
  padding: 32px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  width: 90%;
}

.card-header {
  text-align: center;
  margin-bottom: 20px;
}

.card-title {
  font-size: 24px;
  font-weight: 600;
  color: #1d1d1f;
  margin: 0;
}

.card-subtitle {
  font-size: 14px;
  color: #86868b;
  margin-top: 6px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
}

.block-alert {
  margin-bottom: 12px;
}

.trace-link {
  color: #0071e3;
  font-family: Consolas, 'Liberation Mono', Menlo, monospace;
  text-decoration: none;
}
</style>
