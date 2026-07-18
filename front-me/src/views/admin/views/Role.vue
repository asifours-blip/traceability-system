<template>
<div class="role-box">
  <div class="role-flex">
    <!-- 左侧表单卡片 -->
    <div class="role-card">
      <div class="role-header">
        <h1 class="title">用户角色管理</h1>
        <p class="subtitle">查询、添加或撤销用户角色</p>
      </div>
      <el-form :model="userForm" :rules="rules" ref="userForm" class="role-form">
        <el-form-item prop="type" class="form-item">
          <el-select v-model="userForm.type" placeholder="选择用户类型" class="type-select">
            <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item prop="address" class="form-item">
          <el-input v-model="userForm.address" placeholder="输入用户地址" class="custom-input" />
        </el-form-item>
        <div class="button-group">
          <el-button type="primary" @click="submitQuery('userForm')" class="submit-btn">查询</el-button>
        </div>
      </el-form>
    </div>
    <!-- 右侧结果卡片 -->
    <div v-if="isQueried" class="result-card">
      <div class="result-header">
        <h2 class="result-title">查询结果</h2>
      </div>
      <div class="result-content">
        <p><strong>用户类型：</strong>{{ getTypeLabel(userForm.type) }}</p>
        <p><strong>用户地址：</strong>{{ userForm.address }}</p>
        <p><strong>角色状态：</strong>
          <span v-if="isExistRole" style="color: #52c41a;">已拥有该角色</span>
          <span v-else style="color: #faad14;">未拥有该角色</span>
        </p>
      </div>
      <div class="button-group">
        <el-button v-if="!isExistRole" type="success" @click="addUser" class="action-btn">添加</el-button>
        <el-button v-if="isExistRole" type="danger" @click="deleteUser" class="action-btn">撤销</el-button>
      </div>
    </div>
  </div>
</div>
</template>
<script>
export default {
  name: "Role",
  data() {
    return {
      userForm: {
        type: '',
        address: ''
      },
      rules: {
        address: [
          { required: true, message: '请输入用户地址', trigger: 'blur' },
        ],
        type: [
          { required: true, message: '请选择用户类型', trigger: 'blur' },
        ],
      },
      options: [
        { value: '0', label: '生产商' },
        { value: '1', label: '分销商' },
        { value: '2', label: '零售商' },
      ],
      isExistRole: false,
      isQueried: false
    }
  },
  methods: {
    submitQuery(formName) {
      this.$refs[formName].validate(valid => {
        if (valid) {
          this.getUserRole();
        }
      });
    },
    deleteUser() {
      this.$confirm('此操作将用户撤销该角色?', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        const res = await this.$http.post("/delete/user", this.userForm)
        if (res.code == 200) {
          this.$message.success("用户撤销角色成功")
          this.isExistRole = false;
        } else {
          this.$message.error(res.mes)
        }
      }).catch(() => { })
    },
    addUser() {
      this.$confirm('此操作将用户添加该角色?', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        const res = await this.$http.post("/add/user", this.userForm)
        if (res.code == 200) {
          this.$message.success("用户添加角色成功")
          this.isExistRole = true;
        } else {
          this.$message.error(res.mes)
        }
      }).catch(() => { })
    },
    getUserRole() {
      this.$http.get("/get/user/role", {
        params: this.userForm
      }).then(res => {
        this.isQueried = true;
        if (res.code == 200) {
          this.isExistRole = res.data
        } else {
          this.$message.error(res.mes)
        }
      })
    },
    getTypeLabel(type) {
      const found = this.options.find(item => item.value === type)
      return found ? found.label : ''
    }
  }
}
</script>
<style scoped>
.role-box {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
}

.role-flex {
  display: flex;
  flex-direction: row;
  gap: 32px;
}

.role-card,
.result-card {
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
  width: 360px;
  min-width: 280px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.role-header,
.result-header {
  text-align: center;
  margin-bottom: 32px;
}

.title {
  font-size: 28px;
  font-weight: 600;
  color: #1d1d1f;
  margin: 0;
  letter-spacing: -0.5px;
}

.subtitle {
  font-size: 16px;
  color: #86868b;
  margin-top: 8px;
  font-weight: 400;
}

.role-form {
  margin-top: 20px;
}

.form-item {
  margin-bottom: 20px;
}

:deep(.el-input__inner) {
  height: 48px;
  border-radius: 12px;
  border: 1px solid #d2d2d7;
  background: rgba(255, 255, 255, 0.8);
  font-size: 16px;
  padding: 0 16px;
  transition: all 0.3s ease;
}

:deep(.el-input__inner:focus) {
  border-color: #0071e3;
  box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.1);
}

:deep(.el-select .el-input__inner) {
  padding-right: 30px;
}

.button-group {
  margin-top: 32px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.submit-btn {
  width: 100%;
  height: 48px;
  border-radius: 12px;
  background: #0071e3;
  border: none;
  font-size: 16px;
  font-weight: 500;
  transition: all 0.3s ease;
}

.submit-btn:hover {
  background: #0077ed;
  transform: translateY(-1px);
}

.action-btn {
  width: 100%;
  height: 48px;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 500;
  transition: all 0.3s ease;
}

.result-title {
  font-size: 22px;
  font-weight: 500;
  color: #1d1d1f;
  margin-bottom: 12px;
}

.result-content {
  font-size: 16px;
  color: #333;
  margin-bottom: 24px;
}

:deep(.el-form-item__error) {
  color: #ff3b30;
  font-size: 14px;
  margin-top: 4px;
}

@media (max-width: 900px) {
  .role-flex {
    flex-direction: column;
    gap: 24px;
    align-items: center;
  }

  .role-card,
  .result-card {
    width: 100%;
    min-width: unset;
    padding: 30px 16px;
  }
}
</style>