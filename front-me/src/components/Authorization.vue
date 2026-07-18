<template>
  <div class="authorzation">
    <slot v-if="isAllow(roles, role)"></slot>
  </div>
</template>

<script>
export default {
  name: 'authorzation-view',
  props: {
    roles: {
      type: Array,
      default: () => []
    },
  },
  data() {
    return {
      role: 0
    }
  },
  created() {
    let userInfo = JSON.parse(localStorage.getItem('userInfo'))
    this.role = userInfo.type
  },
  methods: {
    isAllow(roles, role) {
      if (roles.length === 0)
        return true
      for (let i = 0; i < roles.length; i++) {
        if (roles[i] == role) {
          return true
        }
        return false
      }
    }
  },
};
</script>