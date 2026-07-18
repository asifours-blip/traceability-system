import Vue from 'vue'
import Vuex from 'vuex'
import {loadFileBase64} from "@/apis/ipfs";
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
        showImg(state, imgHash) {
            loadFileBase64(imgHash).then(res => {
                state.imageUrl = "data:image/png;base64," + res.data
                state.imgVisible = true
            })
        },
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
