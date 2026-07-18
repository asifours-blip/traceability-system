<template>
  <div class="iot-dashboard">
    <h1>物联网数据总览</h1>
    <el-row :gutter="20">
      <el-col :span="12" v-for="batch in batches" :key="batch.id" style="margin-bottom: 20px">
        <el-card>
          <div slot="header">{{ batch.name }}（溯源码：{{ batch.id }}）</div>
          <IotChart :batchId="batch.id" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import IotChart from '@/components/IotChart.vue'

export default {
  components: { IotChart },
  data() {
    return {
      batches: [
        { id: 'SY60202600001', name: '精品猪肉白菜' },
        { id: 'SY60202600002', name: '前途玉米' },
        { id: 'SY60202600003', name: '超大红薯' }
      ]
    }
  }
}
</script>

<style scoped>
.iot-dashboard {
  padding: 20px;
}
</style>