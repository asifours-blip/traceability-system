<template>
<div class="page-container">
  <BatchTable title="指定给我的批次（分销）" subtitle="只显示生产商指定给本账号的批次；进入详情录入分销信息并指定零售商" my-stage="DISTRIBUTION"></BatchTable>
</div>
</template>

<script>
import BatchTable from '@/components/BatchTable.vue'

// 录入入口在批次详情页：点溯源号进入，只有被指定的账号才会看到录入表单（后端同样校验）
export default {
  name: 'Distributor',
  components: { BatchTable }
}
</script>

<style scoped>
.page-container {
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
  padding: 30px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
</style>
