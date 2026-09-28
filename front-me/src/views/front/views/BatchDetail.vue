<template>
<div class="page-container">
  <div class="detail-card" v-loading="loading">
    <div class="head">
      <div>
        <h2 class="card-title mono">{{ traceNumber }}</h2>
        <p v-if="detail" class="card-subtitle">{{ detail.productName }} · 建档于 {{ formatTime(detail.createdAt) }}</p>
      </div>
      <div class="head-actions">
        <el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button>
        <el-button size="small" @click="$router.push('/traceDetail/' + encodeURIComponent(traceNumber))">消费者视图</el-button>
        <el-button size="small" @click="$router.push('/qrcodeTrace/' + encodeURIComponent(traceNumber))">二维码</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" type="error" :title="loadError" show-icon :closable="false" class="block"></el-alert>

    <template v-if="detail">
      <el-alert v-if="detail.chainError" type="warning" show-icon :closable="false" class="block"
        :title="'链上数据暂时读不到：' + detail.chainError"
        description="下面只显示本系统的交易记录，不代表链上状态；录入与更正暂不可用，请稍后刷新。"></el-alert>

      <div class="parties">
        <div class="party">
          <span class="party-label">生产商</span>
          <span>{{ partyText(detail.producer) }}</span>
        </div>
        <div class="party">
          <span class="party-label">分销商</span>
          <span>{{ partyText(detail.distributor) }}</span>
          <el-button v-if="perm.canAssignDistributor" type="text" size="mini" @click="openAssign('DISTRIBUTION')">变更</el-button>
        </div>
        <div class="party">
          <span class="party-label">零售商</span>
          <span>{{ partyText(detail.retailer) }}</span>
          <el-button v-if="perm.canAssignRetailer" type="text" size="mini" @click="openAssign('RETAIL')">
            {{ detail.retailer ? '变更' : '指定' }}
          </el-button>
        </div>
      </div>

      <div v-for="stage in stages" :key="stage.key" :ref="'stage-' + stage.key"
        class="stage-card" :class="{ active: activeStage === stage.key }" @click="focus(stage.key)">
        <div class="stage-head">
          <h3 class="stage-title">{{ stage.label }}阶段</h3>
          <el-tag :type="meta(stageOf(stage.key)).type" size="small">{{ meta(stageOf(stage.key)).text }}</el-tag>
          <span v-if="stageOf(stage.key).onChain && stageOf(stage.key).status !== 'CONFIRMED'" class="note">
            链上已有该阶段数据（本系统的交易记录尚未确认，可查证）
          </span>
        </div>

        <!-- 交易状态：以回执为准；读链查证确认的没有哈希与块高 -->
        <div class="tx-info">
          <div v-if="stageOf(stage.key).txHash">
            交易哈希 <code class="mono">{{ stageOf(stage.key).txHash }}</code>
            <span v-if="stageOf(stage.key).blockNumber"> · 块高 {{ stageOf(stage.key).blockNumber }}</span>
          </div>
          <div v-else-if="stageOf(stage.key).status === 'CONFIRMED'">已通过读链查证确认上链；未拿到回执，交易哈希与块高未知</div>
          <div v-if="stageOf(stage.key).status === 'FAILED'" class="error-text">失败原因：{{ stageOf(stage.key).errorReason }}</div>
          <div v-if="stageOf(stage.key).status === 'PENDING'" class="warn-text">
            交易记录 #{{ stageOf(stage.key).txId }} 结果未知：{{ stageOf(stage.key).errorReason || '等待结果' }}。查证前不能重复提交。
          </div>
          <div v-if="stageOf(stage.key).status === 'RELEASED'" class="warn-text">
            交易记录 #{{ stageOf(stage.key).txId }} 经查证当时未写入，可以重新提交；原交易若之后才上链，重新提交会被合约拒绝，不会重复写入。
          </div>
          <el-button v-if="stageOf(stage.key).canVerify" size="mini" type="warning" :loading="verifying === stageOf(stage.key).txId"
            @click.stop="verify(stageOf(stage.key))">查证</el-button>
        </div>

        <!-- 链上原始数据 -->
        <el-descriptions v-if="stageOf(stage.key).data" :column="2" size="small" border class="block">
          <el-descriptions-item v-for="f in fields[stage.key]" :key="f.name" :label="f.label">
            <span :class="{ mono: f.cid }">{{ stageOf(stage.key).data[f.name] }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="上链时间">{{ formatTime(stageOf(stage.key).data.timestamp) }}</el-descriptions-item>
          <el-descriptions-item label="链上写入者"><span class="mono">{{ stageOf(stage.key).writer }}</span></el-descriptions-item>
        </el-descriptions>
        <div v-if="stageOf(stage.key).hasFile" class="file-row">
          <span class="party-label">{{ stage.fileLabel }}</span>
          <template v-if="fileOf(stage.key).state === 'BOUND' && fileOf(stage.key).available !== false">
            <el-image v-if="isImage(fileOf(stage.key))" :src="fileUrl(stage)" :preview-src-list="[fileUrl(stage)]" fit="contain" class="thumb">
              <div slot="error" class="thumb-error">文件读取失败</div>
            </el-image>
            <a v-else :href="fileUrl(stage)" target="_blank" rel="noopener"><i class="el-icon-document"></i> {{ fileOf(stage.key).fileName }}</a>
            <div class="file-meta">
              已绑定 · {{ fileOf(stage.key).mimeType }} · {{ sizeText(fileOf(stage.key).size) }}
              <span v-if="fileOf(stage.key).bindSource === 'LEGACY_CHAIN_READ'">（重建时从 IPFS 登记的旧文件）</span>
              <div class="mono">SHA-256 {{ fileOf(stage.key).sha256 }}</div>
              <div v-if="fileOf(stage.key).available === null" class="warn-text">{{ fileOf(stage.key).message }}</div>
            </div>
          </template>
          <el-alert v-else-if="fileOf(stage.key).errorCode === 'FILE_MISSING'" type="error" :closable="false" show-icon
            title="文件缺失" :description="'链上登记了该文件（' + fileOf(stage.key).fileName + '），但存储节点上已找不到内容，请联系管理员恢复。SHA-256 ' + fileOf(stage.key).sha256"></el-alert>
          <el-alert v-else type="warning" :closable="false" show-icon
            :title="fileOf(stage.key).state === 'CONFLICT' ? '文件与链上不一致' : '文件未绑定'"
            :description="fileOf(stage.key).message"></el-alert>
        </div>

        <!-- 链下更正：只追加 -->
        <div v-if="stageOf(stage.key).corrections && stageOf(stage.key).corrections.length" class="corrections">
          <div v-for="c in stageOf(stage.key).corrections" :key="c.id" class="correction">
            <el-tag size="mini" type="warning">链下更正</el-tag>
            <span v-for="f in c.fields" :key="f.field" class="corr-field">{{ f.label }} → <b>{{ f.value }}</b></span>
            <div class="corr-meta">原因：{{ c.reason }} · {{ c.authorCompany || c.authorUsername }} · {{ formatTime(c.createdAt) }}</div>
          </div>
        </div>
        <el-button v-if="perm.canCorrect && perm.canCorrect[stage.key]" size="mini" class="block"
          @click.stop="openCorrection(stage.key)">追加更正</el-button>

        <!-- 录入：只对被指定的账号显示；后端同样校验 -->
        <div v-if="stage.key === 'PRODUCTION' && perm.canProduce" class="action">
          <el-button type="primary" size="small" @click.stop="$router.push({ path: '/producer', query: { traceNumber } })">重新提交生产信息</el-button>
        </div>

        <el-form v-if="stage.key === 'DISTRIBUTION' && perm.canDistribute" ref="distForm" :model="dist" :rules="distRules"
          label-width="110px" class="action" @click.native.stop>
          <el-form-item v-for="f in distTextFields" :key="f.name" :label="f.label" :prop="f.name" :error="serverErrors[f.name]">
            <el-input v-model="dist[f.name]"></el-input>
          </el-form-item>
          <el-form-item label="分销价格" prop="distributePrice" :error="serverErrors.distributePrice">
            <el-input-number v-model="dist.distributePrice" :min="1" :precision="0" :step="1" controls-position="right"></el-input-number>
          </el-form-item>
          <el-form-item label="分销数量" prop="distributeQuantity" :error="serverErrors.distributeQuantity">
            <el-input-number v-model="dist.distributeQuantity" :min="1" :precision="0" :step="1" controls-position="right"></el-input-number>
          </el-form-item>
          <el-form-item label="质检报告" prop="inspectionReport" :error="serverErrors.inspectionReport">
            <ImgUpload v-model="dist.inspectionReport"></ImgUpload>
          </el-form-item>
          <el-form-item label="下游零售商" prop="retailerUsername" :error="serverErrors.retailerUsername">
            <el-select v-model="dist.retailerUsername" filterable placeholder="选择零售商账号">
              <el-option v-for="p in partners" :key="p.username" :label="(p.companyName || p.username) + '（' + p.username + '）'" :value="p.username"></el-option>
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submitDist">{{ submitting ? '等待链上回执…' : '录入分销信息并上链' }}</el-button>
          </el-form-item>
        </el-form>

        <el-form v-if="stage.key === 'RETAIL' && perm.canRetail" ref="retailForm" :model="retail" :rules="retailRules"
          label-width="110px" class="action" @click.native.stop>
          <el-form-item label="零售企业" prop="companyName" :error="serverErrors.companyName">
            <el-input v-model="retail.companyName"></el-input>
          </el-form-item>
          <el-form-item label="零售价格" prop="salePrice" :error="serverErrors.salePrice">
            <el-input-number v-model="retail.salePrice" :min="1" :precision="0" controls-position="right"></el-input-number>
          </el-form-item>
          <el-form-item label="零售数量" prop="saleQuantity" :error="serverErrors.saleQuantity">
            <el-input-number v-model="retail.saleQuantity" :min="1" :max="maxSaleQuantity" :precision="0" controls-position="right"></el-input-number>
            <span class="hint">不超过分销数量 {{ maxSaleQuantity }}</span>
          </el-form-item>
          <el-form-item label="保质期（天）" prop="shelfLife" :error="serverErrors.shelfLife">
            <el-input-number v-model="retail.shelfLife" :min="1" :precision="0" controls-position="right"></el-input-number>
          </el-form-item>
          <el-form-item label="单据号" prop="invoiceNo" :error="serverErrors.invoiceNo">
            <el-input v-model="retail.invoiceNo"></el-input>
          </el-form-item>
          <el-form-item label="销售日期" prop="saleTime" :error="serverErrors.saleTime">
            <el-date-picker v-model="retail.saleTime" type="date" format="yyyy-MM-dd" value-format="yyyy-MM-dd"
              :picker-options="saleDateOptions"></el-date-picker>
            <span class="hint">不早于生产日期 {{ productTime }}，不晚于今天</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submitRetailForm">{{ submitting ? '等待链上回执…' : '录入零售信息并上链' }}</el-button>
          </el-form-item>
        </el-form>
      </div>

      <h3 class="section-title">交接历史</h3>
      <el-timeline v-if="detail.assignments.length">
        <el-timeline-item v-for="(a, i) in detail.assignments" :key="i" :timestamp="formatTime(a.createdAt)" placement="top">
          {{ a.stage === 'DISTRIBUTION' ? '分销商' : '零售商' }}：{{ a.from ? partyText(a.from) : '（首次指定）' }} → {{ partyText(a.to) }}
          <span class="corr-meta">操作人 {{ a.operator }}<template v-if="a.reason">，原因：{{ a.reason }}</template></span>
        </el-timeline-item>
      </el-timeline>
      <p v-else class="corr-meta">暂无</p>
    </template>
  </div>

  <el-dialog :title="assign.stage === 'DISTRIBUTION' ? '变更分销商' : '指定零售商'" :visible.sync="assign.visible" width="440px">
    <p class="corr-meta">只能在该阶段上链之前变更；每次变更都会留下历史。</p>
    <el-form label-width="80px">
      <el-form-item label="账号" :error="serverErrors.username">
        <el-select v-model="assign.username" filterable placeholder="选择账号">
          <el-option v-for="p in assign.options" :key="p.username" :label="(p.companyName || p.username) + '（' + p.username + '）'" :value="p.username"></el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="原因">
        <el-input v-model="assign.reason" maxlength="200" show-word-limit></el-input>
      </el-form-item>
    </el-form>
    <span slot="footer">
      <el-button @click="assign.visible = false">取消</el-button>
      <el-button type="primary" :loading="assign.saving" @click="submitAssign">确定</el-button>
    </span>
  </el-dialog>

  <el-dialog title="追加链下更正" :visible.sync="correction.visible" width="520px">
    <p class="corr-meta">链上记录只能写一次，不会被修改。更正只追加在链下，消费者页会在原始记录旁标注「链下更正」；提交后不能修改或删除。</p>
    <el-form label-width="90px">
      <el-form-item label="更正字段" :error="serverErrors.content">
        <el-select v-model="correction.fields" multiple placeholder="选择要更正的字段">
          <el-option v-for="f in correctionFieldOptions" :key="f.name" :label="f.label" :value="f.name"></el-option>
        </el-select>
      </el-form-item>
      <el-form-item v-for="name in correction.fields" :key="name" :label="labelOf(correction.stage, name)" :error="serverErrors['content.' + name]">
        <el-input v-model="correction.values[name]" placeholder="更正后的值"></el-input>
      </el-form-item>
      <el-form-item label="原因" :error="serverErrors.reason">
        <el-input v-model="correction.reason" type="textarea" maxlength="500" show-word-limit></el-input>
      </el-form-item>
    </el-form>
    <span slot="footer">
      <el-button @click="correction.visible = false">取消</el-button>
      <el-button type="primary" :loading="correction.saving" @click="submitCorrection">追加</el-button>
    </span>
  </el-dialog>
</div>
</template>

<script>
import {
  addCorrection, assignDistributor, assignRetailer, getBatch, listPartners,
  publicFileUrl, submitDistribution, submitRetail, verifyTx
} from '@/apis/trace'
import { STAGES, STAGE_FIELDS, STATUS_META, explainTxResult, formatTime } from '@/utils/traceFields'
import { localStorageService } from '@/utils/commonUtil'

const EMPTY_STAGE = { status: 'NOT_STARTED', corrections: [] }

export default {
  name: 'BatchDetail',
  data() {
    const userInfo = localStorageService.getItem('userInfo') || {}
    const required = message => [{ required: true, message, trigger: 'blur' }]
    return {
      stages: STAGES,
      fields: STAGE_FIELDS,
      detail: null,
      loading: false,
      loadError: '',
      verifying: null,
      submitting: false,
      serverErrors: {},
      partners: [],
      dist: {
        companyName: userInfo.companyName || '', storageCondition: '', transportMethod: '', distributeBatch: '',
        storageLocation: '', distributePrice: undefined, distributeQuantity: undefined, inspectionReport: '', retailerUsername: ''
      },
      distRules: {
        companyName: required('请输入分销企业'),
        storageCondition: required('请输入存储条件'),
        transportMethod: required('请输入运输方式'),
        distributeBatch: required('请输入分销批次'),
        storageLocation: required('请输入仓库地址'),
        distributePrice: [{ required: true, message: '请输入正整数价格', trigger: 'change' }],
        distributeQuantity: [{ required: true, message: '请输入正整数数量', trigger: 'change' }],
        inspectionReport: [{ required: true, message: '请上传质检报告', trigger: 'change' }],
        retailerUsername: [{ required: true, message: '请选择下游零售商', trigger: 'change' }]
      },
      retail: {
        companyName: userInfo.companyName || '', salePrice: undefined, saleQuantity: undefined,
        shelfLife: undefined, invoiceNo: '', saleTime: ''
      },
      retailRules: {
        companyName: required('请输入零售企业'),
        salePrice: [{ required: true, message: '请输入正整数价格', trigger: 'change' }],
        saleQuantity: [{ required: true, message: '请输入正整数数量', trigger: 'change' }],
        shelfLife: [{ required: true, message: '请输入保质期天数', trigger: 'change' }],
        invoiceNo: required('请输入单据号'),
        saleTime: [{ required: true, message: '请选择销售日期', trigger: 'change' }]
      },
      assign: { visible: false, stage: 'DISTRIBUTION', username: '', reason: '', options: [], saving: false },
      correction: { visible: false, stage: 'PRODUCTION', fields: [], values: {}, reason: '', saving: false }
    }
  },
  computed: {
    traceNumber() {
      return this.$route.params.traceNumber
    },
    // 当前定位的阶段在 URL 里（?stage=），刷新或直链后仍回到同一阶段
    activeStage() {
      const s = String(this.$route.query.stage || '').toUpperCase()
      return STAGES.some(x => x.key === s) ? s : 'PRODUCTION'
    },
    perm() {
      return (this.detail && this.detail.permissions) || {}
    },
    distTextFields() {
      return STAGE_FIELDS.DISTRIBUTION.filter(f => ['companyName', 'storageCondition', 'transportMethod', 'distributeBatch', 'storageLocation'].includes(f.name))
    },
    maxSaleQuantity() {
      const d = this.stageOf('DISTRIBUTION').data
      return d ? Number(d.distributeQuantity) : Infinity
    },
    productTime() {
      const d = this.stageOf('PRODUCTION').data
      return d ? d.productTime : ''
    },
    saleDateOptions() {
      const min = this.productTime ? new Date(this.productTime + 'T00:00:00').getTime() : 0
      return { disabledDate: time => time.getTime() > Date.now() || time.getTime() < min }
    },
    correctionFieldOptions() {
      return STAGE_FIELDS[this.correction.stage] || []
    }
  },
  watch: {
    '$route.params.traceNumber'() {
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
      const res = await getBatch(this.traceNumber)
      this.loading = false
      if (res.code !== 200) {
        this.detail = null
        this.loadError = res.code === 403 ? '无权查看该批次：只有本批次的生产商、被指定的分销商和零售商可以查看'
          : res.code === 404 ? '该溯源号未在本系统建档' : res.mes
        return
      }
      this.loadError = ''
      this.detail = res.data
      if ((this.perm.canDistribute || this.perm.canAssignRetailer) && !this.partners.length) {
        const p = await listPartners('RETAILER')
        this.partners = p.code === 200 ? p.data : []
      }
      this.$nextTick(() => this.scrollTo(this.activeStage))
    },
    stageOf(key) {
      if (!this.detail) {
        return EMPTY_STAGE
      }
      return this.detail.stages.find(s => s.stage === key) || EMPTY_STAGE
    },
    meta(s) {
      return STATUS_META[s.status] || STATUS_META.NOT_STARTED
    },
    partyText(p) {
      return p ? `${p.companyName || p.username}（${p.username}）` : '未指定'
    },
    labelOf(stage, name) {
      const f = (STAGE_FIELDS[stage] || []).find(x => x.name === name)
      return f ? f.label : name
    },
    fileOf(key) {
      return this.stageOf(key).file || {}
    },
    isImage(file) {
      return !!file.mimeType && file.mimeType.startsWith('image/')
    },
    sizeText(bytes) {
      if (!bytes && bytes !== 0) {
        return ''
      }
      return bytes > 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(2) + ' MB' : Math.ceil(bytes / 1024) + ' KB'
    },
    fileUrl(stage) {
      return publicFileUrl(this.traceNumber, stage.file)
    },
    focus(key) {
      if (key !== this.activeStage) {
        this.$router.replace({ query: { ...this.$route.query, stage: key } }).catch(() => {})
      }
    },
    scrollTo(key) {
      const el = this.$refs['stage-' + key]
      const node = Array.isArray(el) ? el[0] : el
      // 页面本身是滚动容器（router-view 设了 overflow-y: auto）：只滚它，不带动外层布局
      const container = this.$el
      if (node && container) {
        const top = node.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop - 12
        container.scrollTop = Math.max(0, top)
      }
    },
    async verify(stage) {
      this.verifying = stage.txId
      const res = await verifyTx(stage.txId)
      this.verifying = null
      if (res.code === 200) {
        this.$message({ type: 'info', message: res.data.message, duration: 6000, showClose: true })
      } else {
        this.$message.error(res.mes)
      }
      this.load()
    },
    // 统一处理上链结果：200 / 202 / 409 / 400 / 超时，结束后都刷新详情，状态以后端记录为准
    async handleTx(promise) {
      this.serverErrors = {}
      this.submitting = true
      const res = await promise
      this.submitting = false
      const result = explainTxResult(res)
      this.serverErrors = result.fieldErrors
      this.$message({ type: result.type, message: result.text, duration: 6000, showClose: true })
      this.load()
    },
    submitDist() {
      const form = Array.isArray(this.$refs.distForm) ? this.$refs.distForm[0] : this.$refs.distForm
      form.validate(valid => {
        if (valid && !this.submitting) {
          this.handleTx(submitDistribution({ ...this.dist, traceNumber: this.traceNumber }))
        }
      })
    },
    submitRetailForm() {
      const form = Array.isArray(this.$refs.retailForm) ? this.$refs.retailForm[0] : this.$refs.retailForm
      form.validate(valid => {
        if (valid && !this.submitting) {
          this.handleTx(submitRetail({ ...this.retail, traceNumber: this.traceNumber }))
        }
      })
    },
    async openAssign(stage) {
      this.serverErrors = {}
      const res = await listPartners(stage === 'DISTRIBUTION' ? 'DISTRIBUTOR' : 'RETAILER')
      if (res.code !== 200) {
        this.$message.error(res.mes)
        return
      }
      this.assign = { visible: true, stage, username: '', reason: '', options: res.data || [], saving: false }
    },
    async submitAssign() {
      this.assign.saving = true
      const data = { username: this.assign.username, reason: this.assign.reason }
      const res = this.assign.stage === 'DISTRIBUTION'
        ? await assignDistributor(this.traceNumber, data)
        : await assignRetailer(this.traceNumber, data)
      this.assign.saving = false
      if (res.code === 200) {
        this.assign.visible = false
        this.$message.success('已变更，历史已记录')
        this.detail = res.data
      } else {
        this.serverErrors = explainTxResult(res).fieldErrors
        this.$message.error(res.mes)
      }
    },
    openCorrection(stage) {
      this.serverErrors = {}
      this.correction = { visible: true, stage, fields: [], values: {}, reason: '', saving: false }
    },
    async submitCorrection() {
      const content = {}
      this.correction.fields.forEach(name => { content[name] = this.correction.values[name] })
      this.correction.saving = true
      const res = await addCorrection(this.traceNumber, { stage: this.correction.stage, reason: this.correction.reason, content })
      this.correction.saving = false
      if (res.code === 200) {
        this.correction.visible = false
        this.$message.success('更正已追加')
        this.load()
      } else {
        this.serverErrors = explainTxResult(res).fieldErrors
        this.$message.error(res.mes)
      }
    }
  }
}
</script>

<style scoped>
.page-container {
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
  padding: 30px;
}

.detail-card {
  background: rgba(255, 255, 255, 0.85);
  border-radius: 20px;
  padding: 32px 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  max-width: 1000px;
  margin: 0 auto;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.card-title {
  font-size: 26px;
  font-weight: 600;
  color: #1d1d1f;
  margin: 0;
}

.card-subtitle {
  color: #86868b;
  margin-top: 6px;
}

.mono {
  font-family: Consolas, 'Liberation Mono', Menlo, monospace;
  word-break: break-all;
}

.block {
  margin-top: 12px;
}

.parties {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
  margin: 20px 0;
  padding: 14px 18px;
  background: #f5f7fa;
  border-radius: 12px;
}

.party-label {
  color: #86868b;
  margin-right: 8px;
}

.stage-card {
  border: 1px solid #e4e7ed;
  border-radius: 14px;
  padding: 18px 22px;
  margin-bottom: 18px;
  cursor: default;
}

.stage-card.active {
  border-color: #0071e3;
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.12);
}

.stage-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stage-title {
  margin: 0;
  font-size: 18px;
}

.note,
.hint {
  color: #86868b;
  font-size: 12px;
  margin-left: 8px;
}

.tx-info {
  margin-top: 10px;
  font-size: 13px;
  color: #555;
  line-height: 1.8;
}

.error-text {
  color: #f56c6c;
}

.warn-text {
  color: #e6a23c;
}

.file-row {
  display: flex;
  align-items: center;
  margin-top: 12px;
}

.thumb {
  width: 120px;
  height: 90px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}

.file-meta {
  margin-left: 12px;
  font-size: 12px;
  color: #86868b;
  word-break: break-all;
}

.file-row .el-alert {
  margin-left: 12px;
}

.thumb-error {
  font-size: 12px;
  color: #86868b;
  padding: 30px 8px;
}

.corrections {
  margin-top: 12px;
}

.correction {
  padding: 8px 12px;
  background: #fdf6ec;
  border-radius: 8px;
  margin-bottom: 8px;
}

.corr-field {
  margin-left: 10px;
}

.corr-meta {
  color: #86868b;
  font-size: 12px;
  margin-left: 6px;
}

.action {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #dcdfe6;
}

.section-title {
  margin: 28px 0 12px;
}
</style>
