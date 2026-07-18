<template>
  <div class="home-container">
    <header class="app-header">
      <div class="header-left">
        <el-avatar size="medium" src="/logo.png"></el-avatar>
        <h3 class="app-title">{{ $store.state.systemInfo.name }}</h3>
      </div>
      <el-button type="danger" @click="logout" class="logout-btn">退出</el-button>
    </header>
    <el-container class="app-main-container">
      <el-aside width="220px" class="app-sidebar">
        <el-menu router :default-active="$route.path" class="apple-menu">
          <el-menu-item
              v-for="item in menuList"
              :key="item.index"
              :index="item.index"
          >
            <i :class="item.icon"></i>
            <span slot="title">{{ item.title }}</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="app-content" style="height: calc(100vh - 100px); overflow-y: hidden;">
        <el-page-header @back="goBack" :content="$route.meta.title" class="apple-page-header"></el-page-header>
        <router-view style="height: calc(100vh - 250px); overflow-y: auto;"></router-view>
      </el-main>
    </el-container>
  </div>
</template>

<script>
export default {
  name: 'home-view',
  data() {
    return {
      // 定义所有可能的菜单项及其允许的角色
      allMenus: [
        { index: '/producer', title: '生产商', icon: 'el-icon-s-promotion', roles: ['生产商', '管理员'] },
        { index: '/distributor', title: '分销商', icon: 'el-icon-truck', roles: ['分销商', '管理员'] },
        { index: '/retailer', title: '零售商', icon: 'el-icon-shopping-cart-full', roles: ['零售商', '管理员'] },
        { index: '/trace', title: '溯源查询', icon: 'el-icon-search', roles: ['生产商', '分销商', '零售商', '消费者', '管理员'] },
        { index: '/traceList', title: '溯源档案', icon: 'el-icon-search', roles: ['生产商', '分销商', '零售商', '消费者', '管理员'] },
        { index: '/iot-dashboard', title: '物联网监测', icon: 'el-icon-data-line', roles: ['生产商', '分销商', '零售商', '消费者', '管理员'] },
        { index: '/userCenter', title: '个人中心', icon: 'el-icon-user', roles: ['生产商', '分销商', '零售商', '消费者', '管理员'] },
        { index: '/admin/setting', title: '系统配置', icon: 'el-icon-setting', roles: ['管理员'] },
        { index: '/admin/role', title: '角色分配', icon: 'el-icon-s-custom', roles: ['管理员'] }
      ]
    };
  },
  computed: {
    // 获取当前登录用户的角色
    userRole() {
      const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}');
      const type = userInfo.type; // 0:生产商 1:分销商 2:零售商 3:消费者 4:管理员
      const roleMap = {
        '0': '生产商',
        '1': '分销商',
        '2': '零售商',
        '3': '消费者',
        '4': '管理员'
      };
      return roleMap[type] || '消费者';
    },
    // 根据角色过滤后的菜单列表
    menuList() {
      return this.allMenus.filter(menu => menu.roles.includes(this.userRole));
    }
  },
  methods: {
    logout() {
      this.$message.success('退出成功');
      localStorage.removeItem('userInfo');
      this.$router.push('/login');
    },
    goBack() {
      this.$router.go(-1);
    }
  }
};
</script>
<style lang="scss" scoped>
.home-container {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
}
.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px 30px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}
.header-left {
  display: flex;
  align-items: center;
}
.app-title {
  font-size: 20px;
  font-weight: 600;
  color: #1d1d1f;
  margin-left: 10px;
  letter-spacing: -0.5px;
}
.logout-btn {
  background: #ff3b30;
  border: none;
  color: white;
  border-radius: 8px;
  padding: 8px 15px;
  font-size: 14px;
  transition: all 0.3s ease;
}
.logout-btn:hover {
  background: #ff453a;
  transform: translateY(-1px);
}
.el-avatar {
  border: 1px solid rgba(0, 0, 0, 0.08);
}
.app-main-container {
  flex: 1;
}
.app-sidebar {
  background-color: #f9f9f9;
  border-right: 1px solid #e0e0e0;
  box-shadow: 2px 0 5px rgba(0, 0, 0, 0.02);
}
.apple-menu {
  border-right: none;
  background-color: transparent;
}
.apple-menu .el-menu-item {
  height: 50px;
  line-height: 50px;
  font-size: 15px;
  color: #333;
  font-weight: 500;
  transition: all 0.2s ease;
}
.apple-menu .el-menu-item i {
  color: #86868b;
  margin-right: 10px;
  font-size: 18px;
}
.apple-menu .el-menu-item.is-active {
  background-color: rgba(0, 113, 227, 0.1) !important;
  color: #0071e3;
  border-right: 3px solid #0071e3;
}
.apple-menu .el-menu-item.is-active i {
  color: #0071e3;
}
.apple-menu .el-menu-item:hover {
  background-color: rgba(0, 0, 0, 0.03);
}
.app-content {
  background-color: transparent;
  padding: 20px 30px;
  height: 100%;
  overflow-y: auto;
}
.apple-page-header {
  margin-bottom: 20px;
  padding-bottom: 15px;
  border-bottom: 1px solid #e0e0e0;
}
:deep(.apple-page-header .el-page-header__left .el-icon-back) {
  font-size: 20px;
  color: #0071e3;
}
:deep(.apple-page-header .el-page-header__content) {
  font-size: 20px;
  font-weight: 600;
  color: #1d1d1f;
  margin-left: 10px;
}
@media (max-width: 768px) {
  .app-header {
    padding: 10px 20px;
  }
  .app-title {
    font-size: 18px;
  }
  .logout-btn {
    font-size: 12px;
    padding: 6px 10px;
  }
  .app-sidebar {
    width: 180px !important;
  }
  .apple-menu .el-menu-item {
    font-size: 14px;
    height: 45px;
    line-height: 45px;
  }
  .apple-menu .el-menu-item i {
    font-size: 16px;
  }
  .app-content {
    padding: 15px 20px;
  }
  :deep(.apple-page-header .el-page-header__content) {
    font-size: 18px;
  }
}
</style>