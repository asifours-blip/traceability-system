<template>
<div id="app">
  <router-view />
  <ImgDialog :url="$store.state.imageUrl" :visible="$store.state.imgVisible"
    @popup="res => $store.state.imgVisible = res"></ImgDialog>
</div>
</template>
<script>
import ImgDialog from "@/components/ImgDialog.vue";
export default {
  name: 'App',
  components: {
    ImgDialog
  },
  created() {
    this.$store.commit("fetchSystemInfo")
  },

}
</script>

<style lang="scss">
#app {
  height: 100vh;
}
</style>
