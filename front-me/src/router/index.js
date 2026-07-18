import Vue from 'vue'
import VueRouter from 'vue-router'
import { Message } from 'element-ui'  // 用于提示无权限

Vue.use(VueRouter)

const routes = [
    {
        path: '/',
        redirect: '/login'
    },
    {
        path: '/login',
        name: 'login',
        component: () => import('@/views/Login.vue')
    },
    {
        path: '/register',
        name: 'register',
        component: () => import('@/views/Register.vue')
    },
    {
        path: '/admin',
        name: 'admin',
        component: () => import('@/views/admin/index.vue'),
        redirect: '/setting',
        children: [
            {
                path: '/block',
                name: 'block',
                component: () => import('@/views/admin/views/Block.vue'),
                meta: {
                    title: '区块链浏览器',
                }
            },
            {
                path: '/setting',
                name: 'setting',
                component: () => import('@/views/admin/views/Setting.vue'),
                meta: {
                    title: '系统配置',
                }
            },
            {
                path: '/role',
                name: 'role',
                component: () => import('@/views/admin/views/Role.vue'),
                meta: {
                    title: '角色分配',
                }
            },
        ]
    },
    {
        path: '/front',
        name: 'front',
        component: () => import('@/views/front/index.vue'),
        children: [
            {
                path: '/distributor',
                name: 'distributor',
                component: () => import('@/views/front/views/Distributor.vue'),
                meta: {
                    title: '分销商',
                }
            },
            {
                path: '/producer',
                name: 'producer',
                component: () => import('@/views/front/views/Producer.vue'),
                meta: {
                    title: '生产商',
                }
            },
            {
                path: '/retailer',
                name: 'retailer',
                component: () => import('@/views/front/views/Retailer.vue'),
                meta: {
                    title: '零售商',
                }
            },
            {
                path: '/trace',
                name: 'trace',
                component: () => import('@/views/front/views/Trace.vue'),
                meta: {
                    title: '溯源查询',
                }
            },
            {
                path: '/traceList',
                name: 'trace-list',
                component: () => import('@/views/front/views/TraceList.vue'),
                meta: {
                    title: '溯源档案',
                }
            },
            {
                path: '/traceDetail/:traceNumber',
                name: 'trace-detail',
                component: () => import('@/views/front/views/TraceDetail.vue'),
                meta: {
                    title: '溯源详情',
                }
            },
            {
                path: '/qrcodeTrace/:traceNumber',
                name: 'qrcode-trace',
                component: () => import('@/views/front/views/QrcodeTrace.vue'),
                meta: {
                    title: '扫码溯源',
                }
            },
            {
                path: '/userCenter',
                name: 'user-center',
                component: () => import('@/views/front/views/UserCenter.vue'),
                meta: {
                    title: '个人中心',
                }
            },
            {
                path: '/iot-dashboard',
                name: 'iot-dashboard',
                component: () => import('@/views/front/views/IotDashboard.vue'),
                meta: {
                    title: '物联网监测',
                }
            },
        ]
    },
]

const router = new VueRouter({
    routes,
    mode: 'history'
})

// 全局前置守卫：权限控制
router.beforeEach((to, from, next) => {
    // 获取用户角色类型（0:生产商,1:分销商,2:零售商,3:消费者,4:管理员）
    const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}')
    const userType = userInfo.type // 字符串 '0'~'4'

    // 未登录时允许访问登录和注册页
    if (!userType && to.path !== '/login' && to.path !== '/register') {
        next('/login')
        return
    }
    const roleNameMap = {
        '0': '生产商',
        '1': '分销商',
        '2': '零售商',
        '3': '消费者',
        '4': '管理员'
    }
    const role = roleNameMap[userType] || '消费者'

    // 定义路由访问权限
    // 公共路由（所有角色可访问）
    const publicRoutes = [
        '/trace', '/traceList', '/iot-dashboard', '/userCenter',
        '/traceDetail', '/qrcodeTrace',
        '/login', '/register'
    ]
    // 生产商专属路由
    const producerRoutes = ['/producer']
    // 分销商专属路由
    const distributorRoutes = ['/distributor']
    // 零售商专属路由
    const retailerRoutes = ['/retailer']
    // 管理员专属路由（后台）
    const adminRoutes = ['/admin', '/block', '/setting', '/role']
    // 检查当前路由是否允许访问
    const path = to.path
    // 管理员可访问所有页面
    if (userType === '4') {
        next()
        return
    }
    // 公共路由允许
    if (publicRoutes.some(prefix => path.startsWith(prefix))) {
        next()
        return
    }
    // 生产商访问自己的专属路由
    if (userType === '0' && producerRoutes.some(prefix => path.startsWith(prefix))) {
        next()
        return
    }
    // 分销商访问自己的专属路由
    if (userType === '1' && distributorRoutes.some(prefix => path.startsWith(prefix))) {
        next()
        return
    }
    // 零售商访问自己的专属路由
    if (userType === '2' && retailerRoutes.some(prefix => path.startsWith(prefix))) {
        next()
        return
    }
    // 消费者只能访问公共路由，上面已处理
    // 如果都不符合，则提示无权限并跳转到首页（或登录页）
    Message.error('您没有权限访问该页面')
    // 跳转到允许访问的默认页面（根据角色重定向）
    if (userType === '0') {
        next('/producer')
    } else if (userType === '1') {
        next('/distributor')
    } else if (userType === '2') {
        next('/retailer')
    } else {
        next('/trace')
    }
})

export default router