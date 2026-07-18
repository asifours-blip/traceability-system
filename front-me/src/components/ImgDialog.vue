<template>
<div class="img-dialog">
    <el-dialog :visible.sync="dialogVisible" :title="title" width="50%" :center="true" :show-close="true"
        @close="handleClose">
        <img :src="url" alt="Preview" class="preview-image">
    </el-dialog>
</div>
</template>

<script>
export default {
    name: "img-dialog",
    props: {
        url: {
            type: String,
            required: true
        },
        title: {
            type: String,
            default: '图片预览'
        },
        visible: {
            type: Boolean,
            default: true
        }
    },
    data() {
        return {
            dialogVisible: this.visible
        };
    },
    watch: {
        visible(newVal) {
            this.dialogVisible = newVal;
        },
        dialogVisible(newVal) {
            this.$emit('popup', newVal)
        }
    },
    methods: {
        handleClose() {
            this.dialogVisible = false;
        },
    }
};
</script>

<style lang="scss" scoped>
.preview-image {
    width: 100%;
    height: auto;
    display: block;
    margin: 0 auto;
}
</style>