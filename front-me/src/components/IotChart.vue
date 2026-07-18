<template>
  <div class="iot-chart">
    <div ref="chart" :style="{ height: '400px', width: '100%' }"></div>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { getIotData } from '@/apis/iot'

export default {
  props: {
    batchId: {
      type: String,
      required: true
    }
  },
  data() {
    return {
      chart: null,
      data: []
    }
  },
  mounted() {
    this.fetchData()
    this.timer = setInterval(this.fetchData, 60000) // 每分钟刷新一次
  },
  beforeDestroy() {
    if (this.chart) this.chart.dispose()
    if (this.timer) clearInterval(this.timer)
  },
  methods: {
    async fetchData() {
      try {
        const res = await getIotData(this.batchId, 20)
        // 根据后端实际返回结构调整
        const data = res.data || res
        if (Array.isArray(data)) {
          this.data = data
          this.renderChart()
        }
      } catch (error) {
        console.error('获取物联网数据失败', error)
      }
    },
    renderChart() {
      if (!this.chart) {
        this.chart = echarts.init(this.$refs.chart)
      }
      const temperatureData = this.data.filter(d => d.sensorType === 'temperature')
      const humidityData = this.data.filter(d => d.sensorType === 'humidity')
      const timeLabels = temperatureData.map(d => new Date(d.collectTime).toLocaleTimeString())

      const option = {
        title: { text: '生长环境监测' },
        tooltip: { trigger: 'axis' },
        legend: { data: ['温度(℃)', '湿度(%)'] },
        xAxis: { type: 'category', data: timeLabels },
        yAxis: [
          { type: 'value', name: '温度(℃)' },
          { type: 'value', name: '湿度(%)' }
        ],
        series: [
          {
            name: '温度(℃)',
            type: 'line',
            data: temperatureData.map(d => d.sensorValue),
            smooth: true,
            lineStyle: { color: '#ff5722' }
          },
          {
            name: '湿度(%)',
            type: 'line',
            data: humidityData.map(d => d.sensorValue),
            smooth: true,
            lineStyle: { color: '#2196f3' },
            yAxisIndex: 1
          }
        ]
      }
      this.chart.setOption(option)
    }
  }
}
</script>