<template>
<div class="login-box">
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <img src="/logo.png" alt="logo" class="logo" v-if="$store.state.systemInfo.logo">
        <h1 class="title">{{ $store.state.systemInfo.name }}</h1>
        <p class="subtitle">登录账号</p>
      </div>

      <el-form :model="loginForm" :rules="rules" ref="loginForm" class="login-form">
        <el-form-item prop="type" class="form-item">
          <el-select v-model="loginForm.type" placeholder="选择用户类型" class="type-select">
            <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value">
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item prop="address" v-if="loginForm.type !== '3'" class="form-item">
          <el-input v-model="loginForm.address" placeholder="输入用户地址" class="custom-input">
          </el-input>
        </el-form-item>

        <div class="button-group">
          <el-button type="primary" @click="submitForm('loginForm')" class="submit-btn">
            登录
          </el-button>
          <el-button @click="goRegister" class="register-btn">
            注册账号
          </el-button>
        </div>
      </el-form>
    </div>
  </div>
</div>
</template>

<script>
import { login } from '@/apis/user'
import { localStorageService } from '@/utils/commonUtil';
import { getContractOwner } from '@/apis/owner'

export default {
  name: 'login-view',
  data() {
    return {
      loginForm: {
        address: '',
        type: ''
      },
      rules: {
        address: [
          { required: true, message: '请输入用户地址', trigger: 'blur' },
        ],
        type: [
          { required: true, message: '请输入用户类型', trigger: 'blur' },
        ],
      },
      options: [
        {
          value: '0',
          label: '生产商'
        },
        {
          value: '1',
          label: '分销商'
        },
        {
          value: '2',
          label: '零售商'
        },
        {
          value: '3',
          label: '消费者'
        },
        {
          value: '4',
          label: '管理员'
        }
      ]
    };
  },
  methods: {
    async submitForm(formName) {
      let { type } = this.loginForm
      if (type === '3') {
        const { data } = await getContractOwner()
        this.loginForm.address = data.owner
        localStorageService.setItem('userInfo', this.loginForm)
        this.$message.success('登录成功')
        this.$router.push('/userCenter')
        return
      }
      if (type === '4') {
        const { data } = await getContractOwner()
        if (data.owner !== this.loginForm.address)
          return this.$message.error('您不是管理员')
        localStorageService.setItem('userInfo', this.loginForm)
        this.$router.push('/admin')
        return
      }

      this.$refs[formName].validate(async (valid) => {
        if (valid) {
          const { code,mes } = await login(this.loginForm)
          if (code == 200) {
            this.$message.success('登录成功')
            localStorageService.setItem('userInfo', this.loginForm)
            this.$router.push('/userCenter')
          }else{
            this.$message.error(mes)
          }
        } else {
          console.log('登录失败!');
          return false;
        }
      });
    },
    goRegister() {
      this.$router.push('/register')
    },
  }
};
</script>

<style scoped>
.login-box {
  min-height: 100vh;
  background: url('../assets/imgs/login-bg.png');
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  display: flex;
  align-items: center;
  justify-content: center;
  /* padding: 20px; */
}

.login-container {
  width: 100%;
  max-width: 400px;
}

.login-card {
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.3);
}

.login-header {
  text-align: center;
  margin-bottom: 40px;
}

.logo {
  width: 64px;
  height: 64px;
  margin-bottom: 16px;
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

.login-form {
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

.register-btn {
  width: 100%;
  height: 48px;
  border-radius: 12px;
  background: transparent;
  border: 1px solid #d2d2d7;
  color: #1d1d1f;
  font-size: 16px;
  font-weight: 500;
  transition: all 0.3s ease;
}

.register-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  border-color: #1d1d1f;
}

:deep(.el-form-item__error) {
  color: #ff3b30;
  font-size: 14px;
  margin-top: 4px;
}

@media (max-width: 480px) {
  .login-card {
    padding: 30px 20px;
  }

  .title {
    font-size: 24px;
  }

  .subtitle {
    font-size: 14px;
  }
}
</style>