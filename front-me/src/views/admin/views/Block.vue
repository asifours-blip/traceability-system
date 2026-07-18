<template>
<div class="page-container">
    <!-- 数量统计卡片 -->
    <div class="info-cards-grid">
        <el-card class="info-card" shadow="hover">
            <div class="box">
                <div class="img-wrapper">
                    <img src="../../../assets/imgs/chain/chain-block.png" alt="区块" />
                </div>
                <div class="title-group">
                    <span class="data-value">{{ transationTotal.blockNumber }}</span>
                    <span class="data-label">区块数量</span>
                </div>
            </div>
        </el-card>
        <el-card class="info-card" shadow="hover">
            <div class="box">
                <div class="img-wrapper">
                    <img src="../../../assets/imgs/chain/node.png" alt="节点" />
                </div>
                <div class="title-group">
                    <span class="data-value">{{ transationTotal.nodeTotal }}</span>
                    <span class="data-label">节点数量</span>
                </div>
            </div>
        </el-card>
        <el-card class="info-card" shadow="hover">
            <div class="box">
                <div class="img-wrapper">
                    <img src="../../../assets/imgs/chain/tx.png" alt="交易" />
                </div>
                <div class="title-group">
                    <span class="data-value">{{ transationTotal.txSum }}</span>
                    <span class="data-label">交易数量</span>
                </div>
            </div>
        </el-card>
        <el-card class="info-card" shadow="hover">
            <div class="box">
                <div class="img-wrapper">
                    <img src="../../../assets/imgs/chain/pend-tx.png" alt="待交易" />
                </div>
                <div class="title-group">
                    <span class="data-value">{{ transationTotal.pendTxSum }}</span>
                    <span class="data-label">待交易数量</span>
                </div>
            </div>
        </el-card>
    </div>

    <!-- 节点信息 -->
    <div class="table-card">
        <div class="card-header">
            <h2 class="card-title">节点信息</h2>
            <p class="card-subtitle">区块链网络中的节点状态概览</p>
        </div>
        <el-table :data="nodeList" border class="apple-table">
            <el-table-column show-overflow-tooltip prop="nodeId" label="节点ID" align="center"></el-table-column>
            <el-table-column prop="blockNumber" label="块高" align="center"></el-table-column>
            <el-table-column prop="pbftView" label="pbftView" align="center"></el-table-column>
            <el-table-column prop="status" label="状态" align="center">
                <template slot-scope="scope">
                    <el-tag :type="commColor[scope.row.status == '1' ? '0' : '1']" class="status-tag">
                        {{ nodeStatus[scope.row.status] }}
                    </el-tag>
                </template>
            </el-table-column>
        </el-table>
    </div>

    <!-- 查询区块 -->
    <div class="form-card">
        <div class="card-header">
            <h2 class="card-title">查询区块</h2>
            <p class="card-subtitle">输入块高查询区块详细信息</p>
        </div>
        <el-input placeholder="请输入块高" v-model="blockNum" class="apple-input-with-button">
            <el-button slot="append" icon="el-icon-search" @click.native="searchBlock"></el-button>
        </el-input>
        <div v-if="Object.keys(blockInfo).length !== 0" class="json-display-card">
            <vue-json-pretty :data="blockInfo" :deep="3" :showSelectController="true" :showLine="true"
                :showDoubleQuotes="false" :showLength="true"></vue-json-pretty>
        </div>
    </div>
</div>
</template>

<script>
const commColor = {
    0: "success",
    1: "danger",
}

const nodeStatus = {
    0: "禁用",
    1: "运行",
}

import { getNodeList, getTransactionTotal, getSearchBlock } from "@/apis/block.js"
import 'vue-json-pretty/lib/styles.css';
import VueJsonPretty from 'vue-json-pretty';
export default {
    name: "FrontDruid",
    components: {
        VueJsonPretty
    },
    data() {
        return {
            nodeList: [],
            transationTotal: {},
            blockInfo: {},
            commColor: commColor,
            nodeStatus: nodeStatus,
            blockNum: ''
        }
    },

    async mounted() {
        // 获取节点信息
        let end1 = await getNodeList()
        let end2 = await getTransactionTotal()

        this.nodeList = end1.data
        this.transationTotal = end2.data

    },

    methods: {
        async searchBlock() {
            if (this.blockNum == "") {
                return this.$message.warning('请输入块高进行查询');
            }
            let res = await getSearchBlock(this.blockNum)
            this.blockInfo = res.data
        },
    },
}
</script>

<style lang="scss" scoped>
.page-container {

    background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
    padding: 30px;
    display: flex;
    flex-direction: column;
    gap: 30px;
    align-items: center;
}

.info-cards-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 20px;
    width: 100%;
    max-width: 960px;
}

.info-card {
    background: rgba(255, 255, 255, 0.8);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 16px;
    padding: 20px;
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
    border: 1px solid rgba(255, 255, 255, 0.3);
    display: flex;
    align-items: center;
    justify-content: center;
    text-align: center;
}

.box {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
}

.img-wrapper {
    width: 60px;
    height: 60px;
    margin-bottom: 10px;
}

.img-wrapper img {
    width: 100%;
    height: 100%;
    object-fit: contain;
}

.title-group {
    display: flex;
    flex-direction: column;
}

.data-value {
    font-size: 28px;
    font-weight: 700;
    color: #1d1d1f;
    margin-bottom: 5px;
}

.data-label {
    font-size: 14px;
    color: #86868b;
}

.form-card,
.table-card {
    background: rgba(255, 255, 255, 0.8);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 20px;
    padding: 40px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
    border: 1px solid rgba(255, 255, 255, 0.3);
    width: 100%;
    max-width: 960px;
}

.table-card {
    width: 90%;
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

/* Table Styles */
.apple-table {
    border-radius: 12px;
    overflow: hidden;
    border: 1px solid rgba(0, 0, 0, 0.1);
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.05);
}

:deep(.apple-table th) {
    background-color: #f5f7fa !important;
    color: #333 !important;
    font-weight: 600;
    padding: 14px 0;
    border-color: rgba(0, 0, 0, 0.1) !important;
}

:deep(.apple-table td) {
    padding: 12px 0;
    border-color: rgba(0, 0, 0, 0.05) !important;
}

:deep(.apple-table .el-table__row) {
    transition: background-color 0.2s ease;
}

:deep(.apple-table .el-table__row:hover) {
    background-color: rgba(0, 0, 0, 0.02);
}

.status-tag {
    border-radius: 8px !important;
    height: 28px;
    line-height: 28px;
    font-size: 13px;
    font-weight: 500;
}

/* Search Block Styles */
.apple-input-with-button {
    width: 100%;
    // max-width: 400px;
    margin: 0 auto;
    //display: flex;
}

:deep(.apple-input-with-button .el-input__inner) {
    height: 48px;
    border-radius: 12px 0 0 12px !important;
    border: 1px solid #d2d2d7;
    background: rgba(255, 255, 255, 0.8);
    font-size: 16px;
    padding: 0 16px;
    transition: all 0.3s ease;
}

:deep(.apple-input-with-button .el-input__inner:focus) {
    border-color: #0071e3;
    box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.1);
}

:deep(.apple-input-with-button .el-input-group__append) {
    background-color: #0071e3;
    border-color: #0071e3;
    border-radius: 0 12px 12px 0 !important;
    color: white;
    font-size: 18px;
    padding: 0 15px;
    transition: all 0.3s ease;
}

:deep(.apple-input-with-button .el-input-group__append:hover) {
    background-color: #0077ed;
    border-color: #0077ed;
}

.json-display-card {
    margin-top: 30px;
    background: rgba(249, 249, 249, 0.6);
    border-radius: 12px;
    padding: 20px;
    border: 1px solid rgba(0, 0, 0, 0.08);
    overflow: auto;
}

@media (max-width: 768px) {
    .page-container {
        padding: 20px;
    }

    .info-cards-grid {
        grid-template-columns: 1fr;
    }

    .info-card {
        padding: 15px;
    }

    .data-value {
        font-size: 24px;
    }

    .data-label {
        font-size: 13px;
    }

    .form-card,
    .table-card {
        padding: 30px 20px;
    }

    .card-title {
        font-size: 24px;
    }

    .card-subtitle {
        font-size: 14px;
    }
}

@media (max-width: 480px) {
    .apple-input-with-button {
        flex-direction: column;
    }

    :deep(.apple-input-with-button .el-input__inner) {
        border-radius: 12px 12px 0 0 !important;
    }

    :deep(.apple-input-with-button .el-input-group__append) {
        border-radius: 0 0 12px 12px !important;
        width: 100%;
    }
}
</style>
