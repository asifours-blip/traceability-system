<template>
<div class="page-container">
  <div class="form-card">
    <div class="card-header">
      <h2 class="card-title">分销信息登记</h2>
      <p class="card-subtitle">请填写详细的分销信息</p>
    </div>
    <el-form :model="form" :rules="rules" ref="createForm" label-width="100px" class="apple-form">
      <el-form-item label="溯源码" prop="traceNumber">
        <el-input v-model="form.traceNumber" placeholder="请输入溯源码"></el-input>
      </el-form-item>
      <el-form-item label="公司名称" prop="companyName">
        <el-input v-model="form.companyName" placeholder="请输入公司名称"></el-input>
      </el-form-item>
      <el-form-item label="存储条件" prop="storageCondition">
        <el-input v-model="form.storageCondition" placeholder="请输入存储条件"></el-input>
      </el-form-item>
      <el-form-item label="运输方式" prop="transportMethod">
        <el-input v-model="form.transportMethod" placeholder="请输入运输方式"></el-input>
      </el-form-item>
      <el-form-item label="分销批次" prop="distributeBatch">
        <el-input v-model="form.distributeBatch" placeholder="请输入分销批次"></el-input>
      </el-form-item>
      <el-form-item label="存储地点" prop="storageLocation">
        <el-input v-model="form.storageLocation" placeholder="请输入存储地点"></el-input>
      </el-form-item>
      <el-form-item label="分销价格(w)" prop="distributePrice">
        <el-input-number v-model="form.distributePrice" :min="0" :precision="2" controls-position="right"
          class="full-width-input-number"></el-input-number>
      </el-form-item>
      <el-form-item label="分销数量(kg)" prop="distributeQuantity">
        <el-input-number v-model="form.distributeQuantity" :min="0" :precision="2" controls-position="right"
          class="full-width-input-number"></el-input-number>
      </el-form-item>
      <el-form-item label="检验报告" prop="inspectionReport">
        <ImgUpload v-model="form.inspectionReport"></ImgUpload>
      </el-form-item>

      <Authorization :roles="[1]">
        <el-form-item class="button-group">
          <el-button type="primary" @click="submitForm('createForm')" class="submit-btn">保存</el-button>
          <el-button @click="resetForm('createForm')" class="reset-btn">重置</el-button>
        </el-form-item>
      </Authorization>
    </el-form>
  </div>

  <div class="table-card">
    <div class="card-header">
      <h2 class="card-title">分销记录</h2>
      <p class="card-subtitle">所有分销数据的概览</p>
    </div>
    <el-table v-loading="loading" :data="list" border stripe class="apple-table">
      <el-table-column prop="traceNumber" label="溯源码" width="180">
        <template slot-scope="scope">
          <div class="trace-number-cell">
            <i class="el-icon-document-copy" v-if="scope.row.traceNumber" @click="copyData(scope.row.traceNumber)"></i>
            <span class="copy-text">{{ scope.row.traceNumber }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="companyName" label="公司名称" width="120"></el-table-column>
      <el-table-column prop="storageCondition" label="存储条件" width="120"></el-table-column>
      <el-table-column prop="transportMethod" label="运输方式" width="120"></el-table-column>
      <el-table-column prop="distributeBatch" label="分销批次" width="120"></el-table-column>
      <el-table-column prop="storageLocation" label="存储地点" width="120"></el-table-column>
      <el-table-column prop="distributePrice" label="分销价格(w)" width="120"></el-table-column>
      <el-table-column prop="distributeQuantity" label="分销数量(kg)" width="120"></el-table-column>
      <el-table-column prop="inspectionReport" label="检验报告" width="100">
        <template slot-scope="scope">
          <el-button type="text" @click="$store.commit('showImg', scope.row.inspectionReport)">预览</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="timestamp" label="上链时间" width="180">
        <template slot-scope="scope">
          {{ dateTimeUtils.formatTimestamp(scope.row.timestamp) }}
        </template>
      </el-table-column>
    </el-table>
  </div>
</div>
</template>

<script>
export default {
  name: 'Distributor',
  data() {
    return {
      // 表单数据
      form: {
        traceNumber: '',
        companyName: '',
        storageCondition: '',
        transportMethod: '',
        distributeBatch: '',
        storageLocation: '',
        distributePrice: 0,
        distributeQuantity: 0,
        inspectionReport: ''
      },
      // 表单验证规则
      rules: {
        traceNumber: [
          { required: true, message: '请输入溯源码', trigger: 'blur' }
        ],
        companyName: [
          { required: true, message: '请输入公司名称', trigger: 'blur' }
        ],
        storageCondition: [
          { required: true, message: '请输入存储条件', trigger: 'blur' }
        ],
        transportMethod: [
          { required: true, message: '请输入运输方式', trigger: 'blur' }
        ],
        distributeBatch: [
          { required: true, message: '请输入分销批次', trigger: 'blur' }
        ],
        storageLocation: [
          { required: true, message: '请输入存储地点', trigger: 'blur' }
        ],
        distributePrice: [
          { required: true, message: '请输入分销价格', trigger: 'blur' }
        ],
        distributeQuantity: [
          { required: true, message: '请输入分销数量', trigger: 'blur' }
        ],
        inspectionReport: [
          { required: true, message: '请输入检验报告', trigger: 'blur' }
        ]
      },
      list: [],
      loading: true
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      const res = await this.$http.get("/distributor/list")
      if (res.code === 200) {
        this.list = res.data
        this.loading = false
      } else {
        this.list = []
        this.loading = false
      }
    },
    submitForm(formName) {
      this.$refs[formName].validate(async valid => {
        if (valid) {
          const res = await this.$http.post("/distributor/add", this.form)
          if (res.code === 200) {
            this.$message.success('提交成功')
            this.resetForm(formName)
            this.loadData()
          } else {
            this.$message.error(res.mes)
          }
        } else {
          return false
        }
      })
    },
    resetForm(formName) {
      this.$refs[formName].resetFields()
    }
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

.table-card {
  width: 90%;
}

.form-card {
  width: 800px;
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
:deep(.el-input-number__increase),
:deep(.el-input-number__decrease) {
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
:deep(.el-select .el-input__inner:focus) {
  border-color: #0071e3;
  box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.1);
}

.full-width-input-number {
  width: 100%;
}

:deep(.el-input-number .el-input__inner) {
  text-align: left;
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
