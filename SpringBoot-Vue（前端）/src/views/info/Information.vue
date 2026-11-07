<template>
  <div class="dashboard-container">
    <!-- 标题部分 -->
    <div class="header">
      <h1><i class="el-icon-data-line"></i> 我的信息</h1>
      <p class="subtitle">查看个人账户信息</p>
    </div>

    <!-- 数据卡片部分 -->
    <div class="cards-container">
      <!-- 余额卡片 -->
      <div class="data-card balance-card">
        <div class="card-icon">
          <i class="el-icon-wallet"></i>
        </div>
        <div class="card-content">
          <h3>账户余额</h3>
          <div class="data-value">
            <span class="value">{{ formattedBalance }}</span>
            <span class="unit">元</span>
          </div>

        </div>
        <div class="card-actions">
          <el-button
            type="text"
            style="font-size: 15px;"
            @click="showWithdrawDialog"
          >
            <i class="el-icon-circle-plus"></i> 提现
          </el-button>
        </div>
      </div>

      <!-- 信誉卡片 -->
      <div class="data-card reputation-card">
        <div class="card-icon">
          <i class="el-icon-medal"></i>
        </div>
        <div class="card-content">
          <h3>信誉评分</h3>
          <div class="data-value">
            <el-rate
              v-model="reputation"
              disabled
              show-score
              :colors="['#99A9BF', '#F7BA2A', '#FF9900']"
              score-template="{value}分"
              class="reputation-rate"
            ></el-rate>
          </div>
          <div class="progress- container">
            <el-progress
              :percentage="reputation"
              :color="getReputationColor(reputation)"
              :show-text="false"
            ></el-progress>
            <div class="progress-labels">
              <span>低</span>
              <span>中</span>
              <span>高</span>
            </div>
          </div>
        </div>
        <div class="card-actions">
          <el-button
            type="text"
            @click="fetchReputation"
          >
            <i class="el-icon-document"></i> 查看详情
          </el-button>
        </div>
        <div class="card-actions">
          <el-button
            type="text"
            @click="showReputationTips"
          >
            <i class="el-icon-question"></i> 如何提升
          </el-button>
        </div>
      </div>

      <!-- 虚假报告卡片 -->
      <div class="data-card false-report-card">
        <div class="card-icon">
          <i class="el-icon-warning"></i>
        </div>
        <div class="card-content">
          <h3>误报次数</h3>
          <div class="data-value">
            <span class="value">{{ falseReportCount }}</span>
            <span class="unit">次</span>
          </div>
          <div class="status-message">
            <i :class="getFalseReportIcon(falseReportCount)"></i>
            <span>{{ getFalseReportMessage(falseReportCount) }}</span>
          </div>
        </div>
        <div class="card-actions">
          <el-button
            type="text"
            @click="fetchReportDetails"
          >
            <i class="el-icon-document"></i> 查看详情
          </el-button>
        </div>
      </div>
    </div>

    <!-- 数据图表部分 -->
    <!-- <div class="chart-container">
      <div class="chart-card">
        <h3><i class="el-icon-trend-charts"></i> 近期数据趋势</h3>
        <div class="chart-placeholder">
          <p>图表区域 - 可使用ECharts展示数据变化趋势</p>
          <div class="mock-chart">
            <div class="mock-line" style="height: 60%"></div>
            <div class="mock-line" style="height: 30%"></div>
            <div class="mock-line" style="height: 80%"></div>
            <div class="mock-line" style="height: 45%"></div>
            <div class="mock-line" style="height: 65%"></div>
            <div class="mock-line" style="height: 50%"></div>
            <div class="mock-line" style="height: 70%"></div>
          </div>
        </div>
      </div>
    </div> -->

    <!-- 底部提示 -->
    <!-- <div class="footer-tips">
      <el-alert
        title="提示"
        type="info"
        :closable="false"
        description="定期检查您的数据可以帮助您更好地了解账户状态。如有任何疑问，请联系客服。"
        show-icon
      >
      </el-alert>
    </div> -->

    <!-- 提现对话框 -->
    <el-dialog
      title="提现申请"
      :visible.sync="withdrawDialogVisible"
      width="30%"
    >
      <el-form
        :model="withdrawForm"
        :rules="withdrawRules"
        ref="withdrawForm"
      >
        <el-form-item
          label="提现金额"
          prop="amount"
        >
          <el-input
            v-model="withdrawForm.amount"
            placeholder="请输入提现金额"
            @input="onAmountInput"
          >
            <template slot="append">元</template>
          </el-input>
        </el-form-item>
        <el-form-item
          label="收款账户"
          prop="account"
        >
          <el-select
            v-model="withdrawForm.account"
            placeholder="请选择收款账户"
          >
            <el-option
              v-for="item in bankAccounts"
              :key="item.id"
              :label="`${item.bankName} (${item.cardNumber.slice(-4)})`"
              :value="item.id"
            >
            </el-option>
          </el-select>
        </el-form-item>
      </el-form>
      <span
        slot="footer"
        class="dialog-footer"
      >
        <el-button @click="withdrawDialogVisible = false">取 消</el-button>
        <el-button
          type="primary"
          @click="submitWithdraw"
          :loading="withdrawLoading"
        >确 定</el-button>
      </span>
    </el-dialog>

    <!-- 虚假报告详情对话框 -->
    <el-dialog
      title="虚假报告详情"
      :visible.sync="reportDetailsVisible"
      width="70%"
    >
      <el-table
        :data="reportDetails"
        border
        style="width: 100%"
        v-loading="reportDetailsLoading"
      >

        <!-- 状态（status: 1=AI生成图片, 3=违章） -->
        <el-table-column
          prop="status"
          label="状态"
          width="120"
        >
          <template slot-scope="scope">
            <el-tag :type="getReportStatusTagType(scope.row.status)">
              {{ formatStatus(scope.row.status) }} <!-- 格式化状态 -->
            </el-tag>
          </template>
        </el-table-column>

        <!-- 举报时间（createdTime） -->
        <el-table-column
          prop="createdTime"
          label="举报时间"
          width="180"
          :formatter="formatTime"
        >
        </el-table-column>

        <!-- 图片链接（image_url） -->
        <el-table-column
          label="图片"
          width="180"
        >
          <template slot-scope="scope">
            <el-image
              v-if="scope.row.imageUrl"
              style="width: 100px; height: 60px"
              :src="scope.row.imageUrl"
              :preview-src-list="[scope.row.image_url]"
              fit="cover"
            >
            </el-image>
            <span v-else>无图片</span>
          </template>
        </el-table-column>

        <!-- 其他字段（如 description） -->
        <el-table-column
          prop="description"
          label="问题描述"
        >
        </el-table-column>

        <!-- 检查员ID（inspector_id） -->
        <el-table-column
          prop="inspectorId"
          label="检查员ID"
          width="120"
          :formatter="formatInspectorId"
        >
        </el-table-column>

      </el-table>
    </el-dialog>

    <!-- 信誉分 -->
    <el-dialog
      title="信誉分变动详情"
      :visible.sync="ReputationVisible"
      width="70%"
    >
      <el-table
        :data="ReputationDetails"
        border
        style="width: 100%"
        v-loading="ReputationLoading"
      >

        <el-table-column
          prop="changeNum"
          label="信誉变化"
          width="100"
        />
        <el-table-column
          prop="reason"
          label="变动原因"
        />
        <el-table-column
          prop="createdTime"
          label="变动时间"
          width="180"
          :formatter="formatTime"
        />

      </el-table>
    </el-dialog>
  </div>
</template>

<script>
import { getUserData, withdraw } from "@/api/withdrawals.js";
import { getReportDetails } from "@/api/report.js";
import { getReputation } from "@/api/reputation.js";
import { getBankAccounts, getDefaultCard } from "@/api/bank";
export default {
  name: "UserDashboard",
  data() {
    const validateAmount = (rule, value, callback) => {
      if (!value) {
        return callback(new Error("请输入提现金额"));
      }
      if (Number.isNaN(Number(value))) {
        return callback(new Error("请输入数字值"));
      }
      if (value < 10) {
        return callback(new Error("提现金额不能少于10元"));
      }
      if (value > this.balance) {
        return callback(new Error("提现金额不能超过账户余额"));
      }
      callback();
    };

    return {
      // 用户数据
      balance: 0,
      reputation: 0,
      falseReportCount: 0,

      // 提现相关
      withdrawDialogVisible: false,
      withdrawLoading: false,
      withdrawForm: {
        amount: "",
        account: "",
      },
      withdrawRules: {
        amount: [{ validator: validateAmount, trigger: "blur" }],
        account: [
          { required: true, message: "请选择收款账户", trigger: "change" },
        ],
      },
      bankAccounts: [],

      // 虚假报告相关
      reportDetailsVisible: false,
      reportDetailsLoading: false,
      reportDetails: [],
      //信誉分
      ReputationVisible: false,
      ReputationLoading: false,
      ReputationDetails: [],
    };
  },
  computed: {
    formattedBalance() {
      return this.balance.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    },
  },
  created() {
    this.fetchUserData();
    this.fetchBankAccounts();
  },
  methods: {
    formatInspectorId(row) {
      return row.inspectorId === 0 ? "" : row.inspectorId;
    },
    // 获取用户数据
    async fetchUserData() {
      try {
        const response = await getUserData();
        const data = response.data;
        this.balance = data.balance;
        this.reputation = data.reputation;
        this.falseReportCount = data.falseReportCount;
      } catch (error) {
        this.$message.error("获取用户数据失败: " + error.message);
      }
    },

    // 获取银行账户
    async fetchBankAccounts() {
      try {
        const response = await getBankAccounts();
        this.bankAccounts = response.data;
      } catch (error) {
        this.$message.error("获取银行账户失败: " + error.message);
      }
    },

    onAmountInput() {
      const regex = /^(0|[1-9]\d*)(\.\d{0,2})?$/;
      // const regex = /^(0|[1-9]\d*)(\.\d{0,2})?$|^\.\d{1,2}$/;
      if (!regex.test(this.withdrawForm.amount))
        this.withdrawForm.amount = this.withdrawForm.amount.slice(0, -1); // 删除非法字符
    },
    // 显示提现对话框
    async showWithdrawDialog() {
      this.withdrawDialogVisible = true;
      const card = await getDefaultCard();
      this.$nextTick(() => {
        this.$refs.withdrawForm.resetFields();
        this.withdrawForm.account = card.data.id;
      });
    },

    // 提交提现申请
    async submitWithdraw() {
      this.$refs.withdrawForm.validate(async (valid) => {
        if (valid) {
          this.withdrawLoading = true;
          try {
            const data = new URLSearchParams();
            data.append("amount", this.withdrawForm.amount);
            data.append("accountId", this.withdrawForm.account);
            await withdraw(data);
            this.$message.success("提现申请已提交");
            this.withdrawDialogVisible = false;
            this.fetchUserData(); // 刷新用户数据
          } catch (error) {
            this.$message.error("提现失败: " + error.message);
          } finally {
            this.withdrawLoading = false;
          }
        }
      });
    },
    // 获取信誉分详情
    async fetchReputation() {
      this.ReputationLoading = true;
      this.ReputationVisible = true;
      try {
        const response = await getReputation();
        this.ReputationDetails = response.data;
      } catch (error) {
        this.$message.error("获取信誉分明细失败: " + error.message);
      } finally {
        this.ReputationLoading = false;
      }
    },

    // 获取虚假报告详情
    async fetchReportDetails() {
      this.reportDetailsLoading = true;
      this.reportDetailsVisible = true;
      try {
        const response = await getReportDetails();
        this.reportDetails = response.data;
      } catch (error) {
        this.$message.error("获取报告详情失败: " + error.message);
      } finally {
        this.reportDetailsLoading = false;
      }
    },
    formatTime(row, column, cellValue) {
      if (!cellValue) return "-";
      return new Date(cellValue).toLocaleString(); // 或使用 dayjs/moment 格式化
    },

    // 格式化状态（1=AI生成图片, 3=违章）
    formatStatus(status) {
      const statusMap = {
        1: "AI生成图片",
        4: "非违章误报",
      };
      return statusMap[status] || "未知状态";
    },

    // 设置标签样式（1=info, 3=danger）
    getReportStatusTagType(status) {
      const typeMap = {
        1: "warning",
        4: "danger",
      };
      return typeMap[status] || "warning";
    },
    // 其他辅助方法
    getReputationColor(score) {
      if (score >= 4) return "#67C23A";
      if (score >= 3) return "#E6A23C";
      return "#F56C6C";
    },
    getFalseReportIcon(count) {
      if (count === 0) return "el-icon-success success-icon";
      if (count <= 2) return "el-icon-warning warning-icon";
      return "el-icon-error danger-icon";
    },
    getFalseReportMessage(count) {
      if (count === 0) return "优秀 - 无虚假报告";
      if (count <= 2) return "良好 - 虚假报告较少";
      return "注意 - 虚假报告较多";
    },
    showReputationTips() {
      this.$alert("信誉评分提升方法：长期保持良好记录", "如何提升信誉", {
        confirmButtonText: "知道了",
        customClass: "reputation-tips",
      });
    },
  },
};
</script>

<style scoped>
.success-icon {
  color: #67c23a;
}

.warning-icon {
  color: #e6a23c;
}

.danger-icon {
  color: #f56c6c;
}

/* 可以添加更多自定义样式 */
.dashboard-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
  font-family: "Helvetica Neue", Helvetica, "PingFang SC", "Hiragino Sans GB",
    "Microsoft YaHei", Arial, sans-serif;
}

.header {
  text-align: center;
  margin-bottom: 30px;
  margin-left: -100px; /* 向左移动 100px */
}

.header h1 {
  color: #303133;
  font-size: 28px;
  margin-bottom: 10px;
}

.header h1 i {
  margin-right: 10px;
  color: #409eff;
}

.subtitle {
  color: #909399;
  font-size: 14px;
}

.cards-container {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  margin-bottom: 30px;
}

.data-card {
  flex: 1;
  min-width: 280px;
  margin: 0 10px 20px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  padding: 20px;
  display: flex;
  flex-direction: column;
  transition: all 0.3s ease;
  overflow: hidden; /* 确保内容不会溢出 */
}

.data-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 5px 15px rgba(0, 0, 0, 0.15);
}

.card-icon {
  font-size: 40px;
  margin-bottom: 15px;
}

.balance-card .card-icon {
  color: #67c23a;
}

.reputation-card .card-icon {
  color: #e6a23c;
}

.false-report-card .card-icon {
  color: #f56c6c;
}

.card-content h3 {
  color: #606266;
  font-size: 16px;
  margin-bottom: 15px;
}

.data-value {
  display: flex;
  align-items: baseline;
  margin-bottom: 15px;
}

.data-value .value {
  font-size: 32px;
  font-weight: bold;
  color: #303133;
  margin-right: 5px;
}

.data-value .unit {
  font-size: 14px;
  color: #909399;
}

.trend {
  display: flex;
  align-items: center;
  font-size: 14px;
  color: #909399;
}

.trend i {
  margin-right: 5px;
}

.trend .el-icon-top {
  color: #f56c6c;
}

.trend .el-icon-bottom {
  color: #67c23a;
}

.reputation-rate {
  margin-bottom: 10px;
}

.progress-container {
  margin-top: 10px;
}

.progress-labels {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.status-message {
  display: flex;
  align-items: center;
  font-size: 14px;
  margin-top: 10px;
}

.status-message i {
  margin-right: 5px;
}

.success-icon {
  color: #67c23a;
}

.warning-icon {
  color: #e6a23c;
}

.danger-icon {
  color: #f56c6c;
}

.card-actions {
  margin-top: auto;
  padding-top: 15px;
  text-align: right;
}

.chart-container {
  margin-top: 20px;
}

.chart-card {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  padding: 20px;
}

.chart-card h3 {
  color: #606266;
  font-size: 16px;
  margin-bottom: 20px;
}

.chart-card h3 i {
  margin-right: 10px;
  color: #409eff;
}

.chart-placeholder {
  height: 300px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  color: #909399;
  background: #f9f9f9;
  border-radius: 4px;
}

.mock-chart {
  display: flex;
  align-items: flex-end;
  height: 200px;
  width: 90%;
  margin-top: 20px;
}

.mock-line {
  flex: 1;
  background: #409eff;
  margin: 0 5px;
  border-radius: 3px 3px 0 0;
  min-height: 1px;
}

.footer-tips {
  margin-top: 30px;
}

@media (max-width: 992px) {
  .cards-container {
    flex-direction: column;
  }

  .data-card {
    margin-bottom: 20px;
  }
}
</style>