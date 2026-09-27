<template>
<div class="page-container">
  <div class="form-card">
    <div class="card-header">
      <h2 class="card-title">生产信息登记</h2>
      <p class="card-subtitle">建档即上链；必须指定下游分销商，只有被指定的分销商能录入分销信息</p>
    </div>
    <el-form :model="form" :rules="rules" ref="createForm" label-width="110px" class="apple-form">
      <el-form-item label="溯源号" prop="traceNumber" :error="serverErrors.traceNumber">
        <el-input v-model="form.traceNumber" placeholder="大写字母开头，如 SY20260928-0001" @input="form.traceNumber = (form.traceNumber || '').toUpperCase()">
          <el-button slot="append" @click="form.traceNumber = generate()">生成</el-button>
        </el-input>
      </el-form-item>
      <el-form-item label="公司名称" prop="companyName" :error="serverErrors.companyName">
        <el-input v-model="form.companyName" placeholder="请输入公司名称"></el-input>
      </el-form-item>
      <el-form-item label="产品名称" prop="productName" :error="serverErrors.productName">
        <el-input v-model="form.productName" placeholder="请输入产品名称"></el-input>
      </el-form-item>
      <el-form-item label="生产地点" prop="productionLocation" :error="serverErrors.productionLocation">
        <el-input v-model="form.productionLocation" placeholder="请输入生产地点"></el-input>
      </el-form-item>
      <el-form-item label="品种" prop="variety" :error="serverErrors.variety">
        <el-input v-model="form.variety" placeholder="请输入品种"></el-input>
      </el-form-item>
      <el-form-item label="生产批次" prop="productionBatch" :error="serverErrors.productionBatch">
        <el-input v-model="form.productionBatch" placeholder="请输入生产批次"></el-input>
      </el-form-item>
      <el-form-item label="生产认证" prop="productionCert" :error="serverErrors.productionCert">
        <ImgUpload v-model="form.productionCert"></ImgUpload>
        <div v-if="form.productionCert" class="cid-text">已上传：{{ form.productionCert }}</div>
      </el-form-item>
      <el-form-item label="生产日期" prop="productTime" :error="serverErrors.productTime">
        <el-date-picker v-model="form.productTime" type="date" placeholder="选择生产日期" format="yyyy-MM-dd"
          value-format="yyyy-MM-dd" :picker-options="pastDateOptions" class="full-width-date-picker"></el-date-picker>
      </el-form-item>
      <el-form-item label="下游分销商" prop="distributorUsername" :error="serverErrors.distributorUsername">
        <el-select v-model="form.distributorUsername" filterable placeholder="选择分销商账号" class="full-width-date-picker">
          <el-option v-for="p in partners" :key="p.username" :label="(p.companyName || p.username) + '（' + p.username + '）'" :value="p.username"></el-option>
        </el-select>
      </el-form-item>
      <el-form-item class="button-group">
        <el-button type="primary" :loading="submitting" @click="submitForm('createForm')" class="submit-btn">
          {{ submitting ? '等待链上回执…' : '上链' }}
        </el-button>
        <el-button :disabled="submitting" @click="resetForm('createForm')" class="reset-btn">重置</el-button>
      </el-form-item>
      <p v-if="submitting" class="waiting-tip">链上确认最长约 30 秒，请勿关闭页面或重复提交</p>
    </el-form>
  </div>
  <BatchTable title="我建档的批次" subtitle="只显示本账号建档的批次；点溯源号进入详情查看各阶段状态" my-stage="PRODUCTION" :reload-key="reloadKey"></BatchTable>
</div>
</template>

<script>
import BatchTable from '@/components/BatchTable.vue'
import { listPartners, submitProduction } from '@/apis/trace'
import { TRACE_NUMBER_PATTERN, explainTxResult, generateTraceNumber, pastDateOptions } from '@/utils/traceFields'
import { localStorageService } from '@/utils/commonUtil'

export default {
  name: 'Producer',
  components: { BatchTable },
  data() {
    const userInfo = localStorageService.getItem('userInfo') || {}
    const required = message => [{ required: true, message, trigger: 'blur' }]
    return {
      form: {
        // 从批次详情「重新提交」跳过来时带上溯源号
        traceNumber: this.$route.query.traceNumber || '',
        companyName: userInfo.companyName || '',
        productName: '',
        productionLocation: '',
        variety: '',
        productionBatch: '',
        productionCert: '',
        productTime: '',
        distributorUsername: ''
      },
      // 前端校验只为体验，后端会再按同样规则校验并逐字段返回 400
      rules: {
        traceNumber: [
          { required: true, message: '请输入溯源号', trigger: 'blur' },
          { pattern: TRACE_NUMBER_PATTERN, message: '大写字母开头，只含大写字母、数字、连字符，4-64 位', trigger: 'blur' }
        ],
        companyName: required('请输入公司名称'),
        productName: required('请输入产品名称'),
        productionLocation: required('请输入生产地点'),
        variety: required('请输入品种'),
        productionBatch: required('请输入生产批次'),
        productionCert: [{ required: true, message: '请上传生产认证并点击「上传到服务器」', trigger: 'change' }],
        productTime: [{ required: true, message: '请选择生产日期', trigger: 'change' }],
        distributorUsername: [{ required: true, message: '请选择下游分销商', trigger: 'change' }]
      },
      serverErrors: {},
      partners: [],
      submitting: false,
      reloadKey: 0,
      pastDateOptions
    }
  },
  async created() {
    const res = await listPartners('DISTRIBUTOR')
    if (res.code === 200) {
      this.partners = res.data || []
    } else {
      this.$message.error(res.mes)
    }
  },
  methods: {
    generate() {
      return generateTraceNumber()
    },
    submitForm(formName) {
      this.$refs[formName].validate(async valid => {
        if (!valid || this.submitting) {
          return
        }
        this.serverErrors = {}
        this.submitting = true
        const tn = this.form.traceNumber
        const res = await submitProduction(this.form)
        this.submitting = false
        const result = explainTxResult(res)
        this.serverErrors = result.fieldErrors
        this.$message({ type: result.type, message: result.text, duration: 6000, showClose: true })
        this.reloadKey++
        if (result.goDetail) {
          this.$router.push({ path: '/batch/' + encodeURIComponent(tn), query: { stage: 'PRODUCTION' } })
        }
      })
    },
    resetForm(formName) {
      this.serverErrors = {}
      this.$refs[formName].resetFields()
    },
  }
}
</script>

<style scoped>
.page-container {

  /* Adjust based on your header/footer height */
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
  padding: 30px;
  display: flex;
  flex-direction: column;
  gap: 30px;
  align-items: center;
}

.form-card,
.table-card {
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
  /* width: 100%;
  max-width: 1000px; */
}

.form-card {
  width: 800px;
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

.apple-form {
  padding: 0 20px;
}

:deep(.el-form-item__label) {
  font-size: 15px;
  color: #333;
  font-weight: 500;
}

:deep(.el-input__inner),
:deep(.el-date-editor .el-input__inner) {
  height: 44px;
  line-height: 44px;
  border-radius: 12px;
  border: 1px solid #d2d2d7;
  background: rgba(255, 255, 255, 0.8);
  font-size: 15px;
  padding: 0 16px;
  transition: all 0.3s ease;
}

:deep(.el-input__inner:focus),
:deep(.el-select .el-input__inner:focus),
:deep(.el-date-editor .el-input__inner:focus) {
  border-color: #0071e3;
  box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.1);
}

.full-width-date-picker {
  width: 100%;
}

.button-group {
  margin-top: 30px;
  text-align: center;
}

.submit-btn {
  width: 140px;
  height: 48px;
  border-radius: 12px;
  background: #0071e3;
  border: none;
  font-size: 16px;
  font-weight: 500;
  transition: all 0.3s ease;
  margin-right: 20px;
}

.submit-btn:hover {
  background: #0077ed;
  transform: translateY(-1px);
}

.reset-btn {
  width: 140px;
  height: 48px;
  border-radius: 12px;
  background: transparent;
  border: 1px solid #d2d2d7;
  color: #1d1d1f;
  font-size: 16px;
  font-weight: 500;
  transition: all 0.3s ease;
}

.reset-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  border-color: #1d1d1f;
}

/* Table Styles */
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
}

.trace-number-cell i {
  margin-right: 8px;
  color: #0071e3;
  cursor: pointer;
}

.trace-number-cell .copy-text {
  font-family: 'SF Mono', SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;
  color: #555;
}

.cid-text {
  font-size: 12px;
  color: #86868b;
  word-break: break-all;
}

.waiting-tip {
  text-align: center;
  color: #e6a23c;
  font-size: 13px;
}

@media (max-width: 768px) {

  .form-card,
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
