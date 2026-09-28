<template>
<div class="page-container">
    <div class="query-card">
        <div class="card-header">
            <h2 class="card-title">溯源查询</h2>
            <p class="card-subtitle">输入溯源码、产品名或生产企业进行查询</p>
        </div>
        <el-form :model="form" ref="form" class="apple-form" @submit.native.prevent>
            <el-form-item label="" prop="keyword">
                <el-input v-model.trim="form.keyword" type="textarea" :rows="3" placeholder="溯源号 / 产品名 / 生产企业"
                    class="custom-textarea"></el-input>
            </el-form-item>
            <el-button type="primary" @click="onSubmit" class="submit-btn">查询</el-button>
        </el-form>
    </div>

    <div class="result-card">
        <div class="card-header">
            <h2 class="card-title">查询结果</h2>
            <p class="card-subtitle">只显示公开信息；列表来自读模型，详情页可查看各阶段与交易哈希</p>
        </div>
        <div v-if="!onSearch" class="info-tip">请在左侧查询栏中输入关键字进行查询</div>
        <div v-else-if="error" class="info-tip no-data">{{ error }}</div>
        <div v-else-if="!loading && total === 0" class="info-tip no-data">没有匹配的溯源信息，请确认后重新查询</div>
        <template v-else>
            <el-table v-loading="loading" :data="records" size="small" class="result-table">
                <el-table-column label="溯源号" min-width="150">
                    <template slot-scope="scope">
                        <router-link :to="'/traceDetail/' + encodeURIComponent(scope.row.traceNumber)">{{ scope.row.traceNumber }}</router-link>
                    </template>
                </el-table-column>
                <el-table-column prop="productName" label="产品" min-width="80"></el-table-column>
                <el-table-column prop="companyName" label="生产企业" min-width="100"></el-table-column>
                <el-table-column prop="productTime" label="生产日期" width="100"></el-table-column>
                <el-table-column label="进度" width="80">
                    <template slot-scope="scope">{{ ['', '已生产', '已分销', '已零售'][scope.row.stageReached] }}</template>
                </el-table-column>
            </el-table>
            <el-pagination class="pager" small layout="total, prev, pager, next" :total="total" :current-page="page"
                :page-size="size" @current-change="p => { page = p; search() }"></el-pagination>
        </template>
    </div>
</div>
</template>

<script>
import { searchTrace } from '@/apis/trace'

// 消费者查询（免登录）：后端 /trace/search 分页查读模型，只返回公开字段
export default {
    name: 'trace-view',
    data() {
        return {
            form: {
                keyword: '',
            },
            records: [],
            total: 0,
            page: 1,
            size: 10,
            loading: false,
            error: '',
            onSearch: false,
        };
    },
    methods: {
        onSubmit() {
            if (!this.form.keyword)
                return this.$message.error('请输入关键字');
            this.page = 1
            this.search()
        },
        async search() {
            this.onSearch = true;
            this.loading = true
            const res = await searchTrace({ keyword: this.form.keyword, page: this.page, size: this.size })
            this.loading = false
            if (res.code === 200) {
                this.records = res.data.records
                this.total = res.data.total
                this.error = ''
            } else {
                this.records = []
                this.total = 0
                this.error = res.mes
            }
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

.result-table {
    width: 100%;
}

.pager {
    margin-top: 12px;
    text-align: right;
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