<template>
  <div class="table-card">
    <div class="card-header">
      <h2 class="card-title">{{ title }}</h2>
      <p class="card-subtitle">{{ subtitle }}</p>
    </div>
    <div class="toolbar">
      <el-radio-group v-model="filter" size="small" @change="onFilter">
        <el-radio-button label="all">全部</el-radio-button>
        <el-radio-button label="todo">待我处理</el-radio-button>
      </el-radio-group>
      <div>
        <el-input v-model.trim="keyword" size="small" clearable placeholder="溯源号 / 产品名" class="keyword"
          @keyup.enter.native="onFilter" @clear="onFilter"></el-input>
        <el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="block-alert"></el-alert>
    <el-table v-loading="loading" :data="list" border stripe class="apple-table" empty-text="暂无批次">
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
    <el-pagination class="pager" background layout="total, sizes, prev, pager, next" :total="total"
      :current-page="page" :page-size="size" :page-sizes="[10, 20, 50, 100]"
      @current-change="onPage" @size-change="onSize"></el-pagination>
  </div>
</template>

<script>
import { listBatches } from '@/apis/trace'
import { STAGES, STATUS_META } from '@/utils/traceFields'

// 各角色的批次列表：数据只来自后端 /batches（后端按账号过滤、分页、筛选「待我处理」，前端不做权限判断）
// 后端从读模型取链上进度，不逐条读链；分页参数放在路由 query 里，刷新后保持
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
      total: 0,
      loading: false,
      error: '',
      filter: this.$route.query.filter === 'todo' ? 'todo' : 'all',
      keyword: this.$route.query.keyword || '',
      page: Number(this.$route.query.page) || 1,
      size: Number(this.$route.query.size) || 10
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
      const res = await listBatches({
        page: this.page,
        size: this.size,
        todo: this.filter === 'todo',
        keyword: this.keyword || undefined
      })
      this.loading = false
      if (res.code === 200) {
        this.list = res.data.records || []
        this.total = res.data.total
        this.error = ''
        // 删除或筛选后当前页已超出末页：回到最后一页
        if (this.list.length === 0 && this.total > 0 && this.page > 1) {
          this.page = Math.ceil(this.total / this.size)
          this.syncQuery()
          this.load()
        }
      } else {
        this.list = []
        this.total = 0
        this.error = res.mes
      }
    },
    onFilter() {
      this.page = 1
      this.syncQuery()
      this.load()
    },
    onPage(p) {
      this.page = p
      this.syncQuery()
      this.load()
    },
    onSize(s) {
      this.size = s
      this.page = 1
      this.syncQuery()
      this.load()
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
      const query = {
        ...this.$route.query,
        filter: this.filter === 'todo' ? 'todo' : undefined,
        keyword: this.keyword || undefined,
        page: this.page > 1 ? String(this.page) : undefined,
        size: this.size !== 10 ? String(this.size) : undefined
      }
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

.keyword {
  width: 200px;
  margin-right: 8px;
}

.pager {
  margin-top: 12px;
  text-align: right;
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
