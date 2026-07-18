import Vue from 'vue'
import App from './App.vue'
import router from './router'
import store from './store'
import ElementUI from 'element-ui';
import 'element-ui/lib/theme-chalk/index.css';
import './main.scss'
import request from "@/utils/request";
import Authorization from "@/components/Authorization.vue";
import ImgUpload from "@/components/ImgUpload.vue";
import mixins  from "@/mixins";
Vue.mixin(mixins);
Vue.component('Authorization', Authorization);
Vue.component('ImgUpload', ImgUpload);
Vue.prototype.$http = request;
Vue.use(ElementUI);
Vue.config.productionTip = false
new Vue({
  router,
  store,
  render: h => h(App)
}).$mount('#app')
"// test CI" 
