<template>
  <div>
    <el-upload
        action="#"
        :auto-upload="false"
        :show-file-list="false"
        :on-change="onChange"
        :accept="accept"
    >
      <el-button slot="trigger" size="small" type="success">选取文件</el-button>
      <el-button style="margin-left: 10px;" size="small" type="success" :loading="uploading"
                 @click.stop="submitUpload">上传到服务器
      </el-button>
      <div slot="tip" class="el-upload__tip">PNG / JPEG / WEBP 图片或 PDF，不超过 10 MB；按文件内容判定类型，改扩展名无效</div>
    </el-upload>
    <div v-if="file" class="picked">
      <img v-if="previewUrl" :src="previewUrl" class="preview">
      <span v-else class="file-name"><i class="el-icon-document"></i> {{ file.name }}</span>
      <span class="size">{{ sizeText }}</span>
    </div>
    <div v-if="uploaded" class="uploaded">
      已上传（待交易确认后绑定）：<code>{{ uploaded.cid }}</code>
      <div class="sha">SHA-256 {{ uploaded.sha256 }}</div>
    </div>
  </div>
</template>

<script>
import { uploadFile, MAX_UPLOAD_BYTES, ACCEPT_TYPES, UPLOAD_ERRORS } from "@/apis/ipfs";

// 选取本地文件 → multipart 上传 → v-model 得到 CID。本地预览用 object URL，不把文件读成 Base64
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
      accept: ACCEPT_TYPES,
      file: null,
      previewUrl: '',
      uploading: false,
      uploaded: null
    };
  },
  computed: {
    sizeText() {
      return this.file ? (this.file.size / 1024 / 1024).toFixed(2) + ' MB' : ''
    }
  },
  beforeDestroy() {
    this.revoke()
  },
  methods: {
    revoke() {
      if (this.previewUrl) {
        URL.revokeObjectURL(this.previewUrl)
        this.previewUrl = ''
      }
    },
    onChange(file) {
      this.revoke()
      this.uploaded = null
      this.$emit('input', '')
      if (file.size > MAX_UPLOAD_BYTES) {
        this.file = null
        return this.$message.error('文件超过 10 MB')
      }
      if (file.size === 0) {
        this.file = null
        return this.$message.error('文件为空')
      }
      this.file = file.raw
      if (file.raw.type && file.raw.type.startsWith('image/')) {
        this.previewUrl = URL.createObjectURL(file.raw)
      }
    },
    async submitUpload() {
      if (!this.file) {
        return this.$message.error('请先选取文件');
      }
      this.uploading = true
      const res = await uploadFile(this.file);
      this.uploading = false
      if (res.code !== 200) {
        const code = res.data && res.data.errorCode
        return this.$message.error(UPLOAD_ERRORS[code] || res.mes || '上传失败')
      }
      this.uploaded = res.data
      this.$emit('input', res.data.cid);
      this.$message.success("上传成功");
    }
  },
};
</script>

<style scoped>
.picked {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.preview {
  max-width: 150px;
  height: auto;
}

.size, .sha {
  color: #86868b;
  font-size: 12px;
}

.uploaded {
  margin-top: 6px;
  font-size: 13px;
  word-break: break-all;
}
</style>
