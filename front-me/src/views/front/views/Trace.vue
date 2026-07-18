<template>
<div class="page-container">
    <div class="query-card">
        <div class="card-header">
            <h2 class="card-title">溯源查询</h2>
            <p class="card-subtitle">请输入农产品溯源码进行查询</p>
        </div>
        <el-form :model="form" ref="form" class="apple-form">
            <el-form-item label="" prop="traceNumber">
                <el-input v-model.number="form.traceNumber" type="textarea" :rows="3" placeholder="请输入您想要查询的溯源码"
                    @clear="onSearch = false;" class="custom-textarea"></el-input>
            </el-form-item>
            <el-button type="primary" @click="onSubmit" class="submit-btn">查询</el-button>
        </el-form>
    </div>

    <div class="result-card">
        <div class="card-header">
            <h2 class="card-title">查询结果</h2>
            <p class="card-subtitle">查询到的农产品溯源信息</p>
        </div>
        <div v-if="!onSearch" class="info-tip">请在左侧查询栏中输入溯源码进行查询</div>
        <div v-else-if="Object.keys(detail).length === 0" class="info-tip no-data">该溯源码无对应信息，请确认后重新查询</div>

        <!-- 溯源查询成功后才展示 -->
        <div v-else class="result-actions">
            <el-button type="primary" @click="$router.push({ path: '/traceDetail/' + form.traceNumber })"
                class="detail-btn">查看详情</el-button>
        </div>
    </div>
</div>
</template>

<script>
export default {
    name: 'trace-view',
    data() {
        return {
            form: {
                traceNumber: '', // 输入的溯源码
            },
            detail: [], // 溯源详细信息
            onSearch: false, // 搜索情况
        };
    },
    methods: {
        async onSubmit() {
            if (!this.form.traceNumber)
                return this.$message.error('请输入溯源码');
            this.onSearch = true;
            const { data } = await this.$http.get('/trace/detail/' + this.form.traceNumber)
            this.detail = data || [];
        }
    },
};
</script>

<style lang="scss" scoped>
.page-container {

    background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
    padding: 30px;
    display: flex;
    flex-direction: row;
    /* Changed to row for side-by-side layout */
    gap: 30px;
    align-items: flex-start;
    /* Align items to the top */
    flex-wrap: wrap;
    /* Allow wrapping on smaller screens */
}

.query-card,
.result-card {
    background: rgba(255, 255, 255, 0.8);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 20px;
    padding: 40px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
    border: 1px solid rgba(255, 255, 255, 0.3);
    flex: 1;
    min-width: 300px;
    /* Minimum width for each card */
}

.card-header {
    text-align: center;
    margin-bottom: 30px;
}

.card-title {
    font-size: 28px;
    font-weight: 600;
    color: #1d1d1f;
    margin: 0;
    letter-spacing: -0.5px;
}

.card-subtitle {
    font-size: 16px;
    color: #86868b;
    margin-top: 8px;
    font-weight: 400;
}

.apple-form {
    padding: 0 10px;
    /* Adjusted padding for form */
}

:deep(.el-form-item__label) {
    font-size: 15px;
    color: #333;
    font-weight: 500;
    /* Removed fixed label width to allow input to take full width */
}

:deep(.el-textarea__inner) {
    height: 120px;
    border-radius: 12px;
    border: 1px solid #d2d2d7;
    background: rgba(255, 255, 255, 0.8);
    font-size: 16px;
    padding: 16px;
    transition: all 0.3s ease;
    resize: vertical;
    /* Allow vertical resizing */
}

:deep(.el-textarea__inner:focus) {
    border-color: #0071e3;
    box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.1);
}

.submit-btn {
    width: 100%;
    height: 48px;
    border-radius: 12px;
    background: #0071e3;
    border: none;
    font-size: 16px;
    font-weight: 500;
    transition: all 0.3s ease;
    margin-top: 20px;
    /* Add margin top for consistency */
}

.submit-btn:hover {
    background: #0077ed;
    transform: translateY(-1px);
}

.info-tip {
    font-size: 18px;
    color: #86868b;
    text-align: center;
    padding: 50px 20px;
    background: rgba(249, 249, 249, 0.6);
    border-radius: 12px;
    border: 1px dashed #d2d2d7;
    margin-top: 20px;
}

.info-tip.no-data {
    color: #ff3b30;
    border-color: #ff3b30;
}

.result-actions {
    text-align: center;
    margin-top: 20px;
}

.detail-btn {
    width: 180px;
    height: 48px;
    border-radius: 12px;
    background: #0071e3;
    border: none;
    font-size: 16px;
    font-weight: 500;
    color: white;
    transition: all 0.3s ease;
}

.detail-btn:hover {
    background: #0077ed;
    transform: translateY(-1px);
}

@media (max-width: 768px) {
    .page-container {
        flex-direction: column;
        /* Stack cards vertically on smaller screens */
        padding: 20px;
    }

    .query-card,
    .result-card {
        padding: 30px 20px;
    }

    .card-title {
        font-size: 24px;
    }

    .card-subtitle {
        font-size: 14px;
    }
}
</style>