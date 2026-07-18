<template>
<div class="page-container">
  <div class="form-card">
    <div class="card-header">
      <h2 class="card-title">生产信息登记</h2>
      <p class="card-subtitle">请填写详细的农产品生产信息</p>
    </div>
    <el-form :model="form" :rules="rules" ref="createForm" label-width="100px" class="apple-form">
      <el-form-item label="溯源码" prop="traceNumber">
        <el-input v-model="form.traceNumber" placeholder="请输入溯源码"></el-input>
      </el-form-item>
      <el-form-item label="公司名称" prop="companyName">
        <el-input v-model="form.companyName" placeholder="请输入公司名称"></el-input>
      </el-form-item>
      <el-form-item label="产品名称" prop="productName">
        <el-input v-model="form.productName" placeholder="请输入产品名称"></el-input>
      </el-form-item>
      <el-form-item label="生产地点" prop="productionLocation">
        <el-input v-model="form.productionLocation" placeholder="请输入生产地点"></el-input>
      </el-form-item>
      <el-form-item label="品种" prop="variety">
        <el-input v-model="form.variety" placeholder="请输入品种"></el-input>
      </el-form-item>
      <el-form-item label="生产批次" prop="productionBatch">
        <el-input v-model="form.productionBatch" placeholder="请输入生产批次"></el-input>
      </el-form-item>
      <el-form-item label="生产认证" prop="productionCert">
        <ImgUpload v-model="form.productionCert"></ImgUpload>
      </el-form-item>
      <el-form-item label="生产时间" prop="productTime">
        <el-date-picker v-model="form.productTime" type="date" placeholder="选择生产时间" format="yyyy-MM-dd"
          value-format="yyyy-MM-dd" class="full-width-date-picker"></el-date-picker>
      </el-form-item>
      <Authorization :roles="[0]">
        <el-form-item class="button-group">
          <el-button type="primary" @click="submitForm('createForm')" class="submit-btn">保存</el-button>
          <el-button @click="resetForm('createForm')" class="reset-btn">重置</el-button>
        </el-form-item>
      </Authorization>
    </el-form>
  </div>
  <div class="table-card">
    <div class="card-header">
      <h2 class="card-title">生产记录</h2>
      <p class="card-subtitle">所有生产数据的概览</p>
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
      <el-table-column prop="productName" label="产品名称" width="120"></el-table-column>
      <el-table-column prop="productionLocation" label="生产地点" width="120"></el-table-column>
      <el-table-column prop="variety" label="品种" width="100"></el-table-column>
      <el-table-column prop="productionBatch" label="生产批次" width="120"></el-table-column>
      <el-table-column prop="productionCert" label="生产认证" width="100">
        <template slot-scope="scope">
          <el-button type="text" @click="$store.commit('showImg', scope.row.productionCert)">预览</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="productTime" label="生产时间" width="120"></el-table-column>
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
  name: 'Producer',
  data() {
    return {
      // 表单数据
      form: {
        traceNumber: '',
        companyName: '',
        productName: '',
        productionLocation: '',
        variety: '',
        productionBatch: '',
        productionCert: '',
        productTime: ''
      },
      // 表单验证规则
      rules: {
        traceNumber: [
          { required: true, message: '请输入溯源码', trigger: 'blur' }
        ],
        companyName: [
          { required: true, message: '请输入公司名称', trigger: 'blur' }
        ],
        productName: [
          { required: true, message: '请输入产品名称', trigger: 'blur' }
        ],
        productionLocation: [
          { required: true, message: '请输入生产地点', trigger: 'blur' }
        ],
        variety: [
          { required: true, message: '请输入品种', trigger: 'blur' }
        ],
        productionBatch: [
          { required: true, message: '请输入生产批次', trigger: 'blur' }
        ],
        productionCert: [
          { required: true, message: '请输入生产认证', trigger: 'blur' }
        ],
        productTime: [
          { required: true, message: '请选择生产时间', trigger: 'change' }
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
      const res = await this.$http.get("/producer/list")
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
          const res = await this.$http.post("/producer/add", this.form)
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
