<template>
  <div>
    <el-upload
        action="#"
        :auto-upload="false"
        :show-file-list="false"
        :on-change="changeImageUpload"
        :on-error="changeImageError"
        accept="image/*"
    >
      <el-button slot="trigger" size="small" type="success">选取文件</el-button>
      <el-button style="margin-left: 10px;" size="small" type="success"
                 @click="submitUpload">上传到服务器
      </el-button>
      <div slot="tip" class="el-upload__tip">只能上传jpg/png文件，且不超过20MB</div>
      <img v-if="imageUrl" :src="imageUrl"  style="max-width: 150px; height: auto;margin-left: 5px">
    </el-upload>
  </div>
</template>
<!--TODO 由于获取的img hash值读取ipfs的值显示图片，所以imageUrl是上传的图片url，value是ipfs的hash值-->
<script>
import {uploadFileBase64} from "@/apis/ipfs";
export default {
  name: "ImgUpload",
  props: {
    value: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      imageUrl: ""
    };
  },
  methods: {
    async submitUpload() {
      if (!this.imageUrl)
        return this.$message.error('请上传图片');
      let base64 = this.imageUrl.split(',')[1];
      const {data} = await uploadFileBase64(base64);
      this.$emit('input', data.hash);
      this.$message.success("上传成功");
    },
    changeImageUpload(file) {
      const isLt2M = file.size / 1024 / 1024 < 2;
      if (!isLt2M) {
        this.$message.error('上传轮播图大小不能超过 2MB!');
      } else {
        const reader = new FileReader();
        reader.onload = (e) => {
          this.imageUrl = e.target.result;
        };
        reader.readAsDataURL(file.raw);
      }
    },
    changeImageError() {
      this.$message.error('上传失败!');
    }
  },
};
</script>

<style scoped>
</style>