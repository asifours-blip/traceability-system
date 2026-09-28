<template>
  <div class="read-model">
    <el-card class="block">
      <div slot="header">重建读模型</div>
      <p class="hint">
        列表与消费者查询都从读模型取数，不逐条读链。重建会遍历链上 getAgroFoodList，按 getStageActors 与各阶段读函数重写读模型；
        顺带为没有归属记录的旧批次按链上写入者地址匹配账号回填归属，并按链上 CID 补齐文件绑定。重复执行结果不变。
      </p>
      <el-button type="primary" :loading="rebuilding" @click="rebuild">{{ rebuilding ? '重建中…' : '重建读模型' }}</el-button>
      <el-button :loading="cleaning" @click="cleanup">立即清理孤儿文件</el-button>
      <el-descriptions v-if="report" :column="3" size="small" border class="block report">
        <el-descriptions-item label="链上溯源号">{{ report.onChain }}</el-descriptions-item>
        <el-descriptions-item label="新增 / 更新 / 未变">{{ report.rows.created }} / {{ report.rows.updated }} / {{ report.rows.unchanged }}</el-descriptions-item>
        <el-descriptions-item label="删除残留">{{ report.removedStale }}</el-descriptions-item>
        <el-descriptions-item label="已认领 / 部分 / 未认领">{{ report.claims.CLAIMED }} / {{ report.claims.PARTIAL }} / {{ report.claims.UNCLAIMED }}</el-descriptions-item>
        <el-descriptions-item label="回填批次 / 下游">{{ report.batchesBackfilled }} / {{ report.partnersBackfilled }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ report.elapsedMs }} ms</el-descriptions-item>
        <el-descriptions-item label="文件" :span="3">{{ filesText }}</el-descriptions-item>
        <el-descriptions-item v-if="report.errors.length" label="错误" :span="3">
          <div v-for="e in report.errors" :key="e.traceNumber">{{ e.traceNumber }}：{{ e.message }}</div>
        </el-descriptions-item>
      </el-descriptions>
      <p v-if="cleanupReport" class="hint">
        孤儿清理：新标记 {{ cleanupReport.orphaned }}，交易未决受保护 {{ cleanupReport.protectedByTx }}，
        取消 pin {{ cleanupReport.unpinned }}，同一 CID 仍被引用而保留 {{ cleanupReport.keptSharedCid }}，失败待重试 {{ cleanupReport.unpinFailed }}
      </p>
    </el-card>

    <el-card class="block">
      <div slot="header">未认领批次</div>
      <p class="hint">
        「未认领」：本系统没有该批次的归属记录（链上生产阶段写入者没有对应的生产商账号）；
        「部分认领」：有归属记录，但某阶段的链上写入者与本系统指定的账号对不上。为写入者地址建好账号后再重建即可认领。
      </p>
      <el-table v-loading="loading" :data="records" border size="small" empty-text="没有未认领的批次">
        <el-table-column prop="traceNumber" label="溯源号" min-width="150"></el-table-column>
        <el-table-column prop="productName" label="产品" min-width="80"></el-table-column>
        <el-table-column label="状态" width="90">
          <template slot-scope="scope">
            <el-tag size="mini" :type="scope.row.claimStatus === 'UNCLAIMED' ? 'danger' : 'warning'">
              {{ scope.row.claimStatus === 'UNCLAIMED' ? '未认领' : '部分认领' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已上链到" width="80">
          <template slot-scope="scope">{{ ['', '生产', '分销', '零售'][scope.row.stageReached] }}</template>
        </el-table-column>
        <el-table-column prop="claimNote" label="原因" min-width="260"></el-table-column>
        <el-table-column label="生产写入者" min-width="140">
          <template slot-scope="scope"><span class="mono">{{ scope.row.producerAddress }}</span></template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next" :total="total" :current-page="page"
        :page-size="size" @current-change="p => { page = p; load() }"></el-pagination>
    </el-card>
  </div>
</template>

<script>
import { rebuildReadModel, listUnclaimed, cleanupOrphans } from '@/apis/trace'

// 管理员：重建读模型、查看未认领批次、手动清理孤儿文件（后端均校验 ADMIN）
export default {
  name: 'ReadModel',
  data() {
    return {
      rebuilding: false,
      cleaning: false,
      report: null,
      cleanupReport: null,
      records: [],
      total: 0,
      page: 1,
      size: 10,
      loading: false
    }
  },
  computed: {
    filesText() {
      const f = this.report && this.report.files
      if (!f || Object.keys(f).length === 0) {
        return '无'
      }
      const names = {
        BOUND: '已绑定', IMPORTED: '从 IPFS 导入', ALREADY_BOUND: '原已绑定', MISSING: 'IPFS 中缺失',
        REJECTED: '类型或大小不合规', UNAVAILABLE: 'IPFS 不可用', CONFLICT: '与链上不一致', NO_CANDIDATE: '无可绑定记录'
      }
      return Object.keys(f).map(k => `${names[k] || k} ${f[k]}`).join('，')
    }
  },
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      const res = await listUnclaimed({ page: this.page, size: this.size })
      this.loading = false
      if (res.code === 200) {
        this.records = res.data.records
        this.total = res.data.total
      } else {
        this.$message.error(res.mes)
      }
    },
    async rebuild() {
      this.rebuilding = true
      const res = await rebuildReadModel()
      this.rebuilding = false
      if (res.code === 200) {
        this.report = res.data
        this.$message.success('重建完成')
        this.page = 1
        this.load()
      } else {
        this.$message.error(res.mes)
      }
    },
    async cleanup() {
      this.cleaning = true
      const res = await cleanupOrphans()
      this.cleaning = false
      if (res.code === 200) {
        this.cleanupReport = res.data
      } else {
        this.$message.error(res.mes)
      }
    }
  }
}
</script>

<style scoped>
.block {
  margin-bottom: 16px;
}

.hint {
  color: #86868b;
  font-size: 13px;
  line-height: 1.6;
}

.report {
  margin-top: 12px;
}

.pager {
  margin-top: 12px;
  text-align: right;
}

.mono {
  font-family: Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}
</style>
