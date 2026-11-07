<template>
  <div class="balance-change-container">
    <div class="header">
      <h2>余额变动记录</h2>
      <div class="summary">
        <div class="summary-item">
          <span class="label">总提现</span>
          <span class="value withdraw">{{ summary.withdraw || '0.00' }}</span>
        </div>
        <div class="summary-item">
          <span class="label">总奖励</span>
          <span class="value reward">{{ summary.reward || '0.00' }}</span>
        </div>
        <div class="summary-item">
          <span class="label">总退款</span>
          <span class="value refund">{{ summary.refund || '0.00' }}</span>
        </div>
      </div>
    </div>

    <div class="log-table">
      <el-table
        :data="balanceLogs"
        style="width: 100%"
        :header-cell-style="{background:'#f5f7fa',color:'#606266'}"
        stripe
        v-loading="loading"
      >
        <el-table-column
          prop="createdTime"
          label="时间"
          width="180"
          :formatter="formatDate"
        ></el-table-column>
        
        <el-table-column
          prop="changeType"
          label="类型"
          width="120"
        >
          <template slot-scope="scope">
            <el-tag
              :type="getTagType(scope.row.changeType)"
              size="medium"
            >
              {{ scope.row.changeType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="amount"
          label="支出"
          width="150"
        >
          <template slot-scope="scope">
            <span :class="getAmountClass(scope.row.amount)">
              {{ formatAmount(scope.row.amount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="余额变化">
          <template slot-scope="scope">
            <div class="balance-change">
              <span class="balance-before">{{ scope.row.balanceBefore.toFixed(2) }}</span>
              <i class="el-icon-right"></i>
              <span class="balance-after">{{ scope.row.balanceAfter.toFixed(2) }}</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="pagination">
      <el-pagination
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
        :current-page="pagination.currentPage"
        :page-sizes="[10, 20, 50, 100]"
        :page-size="pagination.pageSize"
        layout="total, sizes, prev, pager, next, jumper"
        :total="pagination.total"
      ></el-pagination>
    </div>
  </div>
</template>

<script>


import { getBalanceDetails ,balanceChangeSummary} from "@/api/moneydetails";

export default {
  data() {
    return {
      balanceLogs: [],
      summary: {
        withdraw: 0,
        reward: 0,
        refund: 0,
      },
      loading: false,
      pagination: {
        currentPage: 1,
        pageSize: 10,
        total: 0,
      },
    };
  },
  created() {
    this.fetchBalanceLogs();
    this.fetchSummary();
  },
  methods: {
    async fetchBalanceLogs() {
      this.loading = true;
      try {
        const response = await getBalanceDetails({
          page: this.pagination.currentPage,
          size: this.pagination.pageSize,
        });

       // debugger;
        this.balanceLogs = response.data.records || [];
        this.pagination.total = response.data.total || 0;
      } catch (error) {
        console.error("获取余额变动记录失败:", error);
        this.$message.error("获取余额变动记录失败");
      } finally {
        this.loading = false;
      }
    },

    async fetchSummary() {
      try {
        const response = await balanceChangeSummary();
        this.summary = response.data || {};
      } catch (error) {
        console.error("获取汇总数据失败:", error);
        this.$message.error("获取汇总数据失败");
      }
    },

    handleSizeChange(val) {
      this.pagination.pageSize = val;
      this.fetchBalanceLogs();
    },

    handleCurrentChange(val) {
      this.pagination.currentPage = val;
      this.fetchBalanceLogs();
    },

    formatDate(row, column, cellValue) {
      if (!cellValue) return "";
      const date = new Date(cellValue);
      return date.toLocaleString();
    },

    formatAmount(amount) {
      if (amount === undefined || amount === null) return "0.00";
      return amount > 0 ? `+${amount.toFixed(2)}` : amount.toFixed(2);
    },

    getTagType(type) {
      switch (type) {
        case "提现":
          return "danger";
        case "奖励":
          return "success";
        case "退款":
          return "warning";
        default:
          return "info";
      }
    },

    getAmountClass(amount) {
      if (amount === undefined || amount === null) return "";
      return amount > 0 ? "positive" : "negative";
    },
  },
};
</script>

<style scoped>
.balance-change-container {
  padding: 20px;
  background-color: #fff;
  border-radius: 4px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
}

.header {
  margin-bottom: 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header h2 {
  color: #303133;
  font-size: 20px;
  margin: 0;
}

.summary {
  display: flex;
  gap: 20px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 20px;
  background-color: #f5f7fa;
  border-radius: 4px;
  min-width: 100px;
}

.summary-item .label {
  font-size: 14px;
  color: #909399;
  margin-bottom: 5px;
}

.summary-item .value {
  font-size: 18px;
  font-weight: bold;
}

.summary-item .withdraw {
  color: #f56c6c;
}

.summary-item .reward {
  color: #67c23a;
}

.summary-item .refund {
  color: #e6a23c;
}

.log-table {
  margin-bottom: 20px;
}

.balance-change {
  display: flex;
  align-items: center;
}

.balance-before {
  color: #909399;
}

.balance-after {
  color: #67c23a;
  font-weight: bold;
}

.el-icon-right {
  margin: 0 10px;
  color: #c0c4cc;
}

.positive {
  color: #67c23a;
  font-weight: bold;
}

.negative {
  color: #f56c6c;
  font-weight: bold;
}

.pagination {
  display: flex;
  justify-content: flex-end;
}
</style>