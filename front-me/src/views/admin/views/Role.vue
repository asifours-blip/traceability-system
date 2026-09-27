<template>
<div class="role-box">
  <div class="role-flex">
    <!-- 左侧：新建账号 -->
    <div class="role-card">
      <div class="role-header">
        <h1 class="title">新建账号</h1>
        <p class="subtitle">由管理员签名在链上授予角色</p>
      </div>
      <el-form :model="userForm" :rules="rules" ref="userForm" class="role-form">
        <el-form-item prop="username" class="form-item">
          <el-input v-model="userForm.username" placeholder="用户名（字母、数字、下划线）" class="custom-input" />
        </el-form-item>
        <el-form-item prop="password" class="form-item">
          <el-input v-model="userForm.password" type="password" show-password placeholder="初始密码（至少 8 位）" class="custom-input" />
        </el-form-item>
        <el-form-item prop="role" class="form-item">
          <el-select v-model="userForm.role" placeholder="选择角色" class="type-select">
            <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item prop="chainAddress" class="form-item">
          <el-input v-model="userForm.chainAddress" placeholder="链上地址（须已在 WeBASE-Front 托管私钥）" class="custom-input" />
        </el-form-item>
        <el-form-item prop="companyName" class="form-item">
          <el-input v-model="userForm.companyName" placeholder="公司/组织名" class="custom-input" />
        </el-form-item>
        <div class="button-group">
          <el-button type="primary" @click="submitCreate('userForm')" class="submit-btn">创建</el-button>
        </div>
      </el-form>
    </div>
    <!-- 右侧：账号列表 -->
    <div class="result-card list-card">
      <div class="result-header">
        <h2 class="result-title">账号列表</h2>
      </div>
      <el-table :data="users" size="small" class="user-table">
        <el-table-column prop="username" label="用户名" min-width="100" />
        <el-table-column label="角色" min-width="70">
          <template slot-scope="scope">{{ getRoleLabel(scope.row.role) }}</template>
        </el-table-column>
        <el-table-column prop="companyName" label="公司/组织" min-width="100" />
        <el-table-column label="链上地址" min-width="120">
          <template slot-scope="scope">
            <el-tooltip :content="scope.row.chainAddress" placement="top">
              <span>{{ shortAddress(scope.row.chainAddress) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="60">
          <template slot-scope="scope">
            <span v-if="scope.row.enabled" style="color: #52c41a;">启用</span>
            <span v-else style="color: #faad14;">停用</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="140">
          <template slot-scope="scope">
            <template v-if="scope.row.role !== 'ADMIN'">
              <el-button type="text" @click="checkChainRole(scope.row)">核对链上角色</el-button>
              <!-- 停用失败时（链上撤销未成功）可对已停用账号重试 -->
              <el-button type="text" class="danger-text" @click="disable(scope.row)">
                {{ scope.row.enabled ? '停用' : '重试撤销' }}
              </el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</div>
</template>
<script>
import { listUsers, createUser, disableUser, getChainRole } from '@/apis/user'

export default {
  name: "Role",
  data() {
    return {
      userForm: {
        username: '',
        password: '',
        role: '',
        chainAddress: '',
        companyName: ''
      },
      rules: {
        username: [
          { required: true, message: '请输入用户名', trigger: 'blur' },
        ],
        password: [
          { required: true, message: '请输入初始密码', trigger: 'blur' },
          { min: 8, message: '密码至少 8 位', trigger: 'blur' },
        ],
        role: [
          { required: true, message: '请选择角色', trigger: 'blur' },
        ],
        chainAddress: [
          { required: true, message: '请输入链上地址', trigger: 'blur' },
        ],
      },
      options: [
        { value: 'PRODUCER', label: '生产商' },
        { value: 'DISTRIBUTOR', label: '分销商' },
        { value: 'RETAILER', label: '零售商' },
      ],
      users: []
    }
  },
  created() {
    this.fetchUsers();
  },
  methods: {
    async fetchUsers() {
      const res = await listUsers()
      if (res.code == 200) {
        this.users = res.data
      }
    },
    submitCreate(formName) {
      this.$refs[formName].validate(async valid => {
        if (!valid) {
          return;
        }
        const res = await createUser(this.userForm)
        if (res.code == 200) {
          this.$message.success("账号已创建，链上角色已授予")
          this.$refs[formName].resetFields()
          this.fetchUsers()
        } else {
          this.$message.error(res.mes)
        }
      });
    },
    disable(row) {
      this.$confirm(`停用 ${row.username}：撤销其全部登录并由管理员签名撤销链上角色，是否继续？`, '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        const res = await disableUser(row.id)
        if (res.code == 200) {
          this.$message.success("账号已停用，链上角色已撤销")
        } else {
          this.$message.error(res.mes)
        }
        this.fetchUsers()
      }).catch(() => { })
    },
    async checkChainRole(row) {
      const res = await getChainRole({ address: row.chainAddress, role: row.role })
      if (res.code == 200) {
        this.$message.info(res.data ? '链上已拥有该角色' : '链上未拥有该角色')
      } else {
        this.$message.error(res.mes)
      }
    },
    getRoleLabel(role) {
      const found = this.options.find(item => item.value === role)
      return found ? found.label : (role === 'ADMIN' ? '管理员' : role)
    },
    shortAddress(address) {
      return address ? address.substring(0, 8) + '...' + address.substring(address.length - 6) : ''
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

.list-card {
  width: 720px;
  justify-content: flex-start;
}

.danger-text {
  color: #ff3b30;
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