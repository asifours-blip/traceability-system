import Vue from 'vue'
import Vuex from 'vuex'
import {getSystemInfo} from '@/apis/systemInfo';

Vue.use(Vuex)

export default new Vuex.Store({
    state: {
        imageUrl: '',
        imgVisible: false,
        systemInfo: {}
    },
    getters: {},
    mutations: {
        fetchSystemInfo(state) {
            getSystemInfo().then(response => {
                state.systemInfo = {...response.data};
                document.title = state.systemInfo.name;
            });
        }
    },
    actions: {

    },
    modules: {}
})
