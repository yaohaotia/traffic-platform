<template>
  <div class="withdrawal-manage">
    <div class="container">
      <div class="handle-box" v-if="hasCheckPermission">
        <el-input
          v-model="query.user_id"
          clearable
          placeholder="请输入用户ID"
          class="handle-input mr10"
        ></el-input>
        <el-select
          v-model="query.status"
          placeholder="请选择状态"
          clearable
          class="mr10"
          style="width: 150px"
        >
          <el-option
            v-for="item in statusOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-date-picker
          class="mr10"
          v-model="dateRange"
          value-format="yyyy-MM-dd HH:mm:ss"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
        ></el-date-picker>

        <el-button
          type="primary"
          icon="el-icon-search"
          @click="handleSearch"
        >搜索</el-button>
        <el-button
          icon="el-icon-refresh"
          @click="handleReset"
        >重置</el-button>
      </div>
      <el-table
        :data="tableData"
        border
        class="table"
        header-cell-class-name="table-header"
      >
        <el-table-column
          prop="userId"
          label="用户ID"
          width="100"
          align="center"
        ></el-table-column>
        <el-table-column
          label="金额"
          width="120"
          align="center"
        >
          <template slot-scope="scope">
            {{ scope.row.amount | currency }}
          </template>
        </el-table-column>
       
        <el-table-column
          prop="requestTime"
          label="申请时间"
          width="160"
          align="center"
        ></el-table-column>
        <el-table-column
          prop="processedTime"
          label="处理时间"
          width="160"
          align="center"
        ></el-table-column>
        <el-table-column
          prop="adminId"
          label="管理员ID"
          width="120"
          align="center"
        ></el-table-column>
        
        <el-table-column
          prop="note"
          label="备注"
          width="220"
          show-overflow-tooltip
        ></el-table-column>

         <el-table-column
          label="状态"
          width="120"
          align="center"
        >
          <template slot-scope="scope">
            <el-tag :type="getStatusTagType(scope.row.status)">
              {{ getStatusText(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="180"
          align="center"
        >
          <template slot-scope="scope">
            <!-- 详情按钮 -->
            <div style="display: flex; justify-content: start; width: 100%;">
              <el-button
                type=""
                size="mini"
                icon="el-icon-view"
                @click="handleDetail(scope.row)"
                style="margin-right: 10px;"
              >详情</el-button>

                <el-button
                v-if="scope.row.status === 'PENDING' && hasCheckPermission"
                type="primary"
                size="mini"
                icon="el-icon-check"
                @click="handleReview(scope.row, 'APPROVED')"
                style="margin-right: 10px;"
              >审批</el-button>
            </div>

            <!-- 审核操作按钮 -->
           
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :current-page="query.page"
          :page-size="query.size"
          :total="total"
          @current-change="handlePageChange"
        ></el-pagination>
      </div>
    </div>

    <!-- 详情对话框 -->
    <el-dialog
      title="提现申请详情"
      :visible.sync="detailVisible"
      width="60%"
    >
      <el-descriptions
        :column="2"
        border
      >
        <el-descriptions-item label="用户ID">{{ currentWithdrawal.userId }}</el-descriptions-item>
        <el-descriptions-item label="申请金额">
          {{ currentWithdrawal.amount | currency }}
        </el-descriptions-item>
        <el-descriptions-item label="申请状态">
          <el-tag :type="getStatusTagType(currentWithdrawal.status)">
            {{ getStatusText(currentWithdrawal.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="申请时间">{{ currentWithdrawal.requestTime }}</el-descriptions-item>
        <el-descriptions-item
          label="处理时间"
          v-if="currentWithdrawal.processedTime"
        >
          {{ currentWithdrawal.processedTime }}
        </el-descriptions-item>
        <el-descriptions-item
          label="管理员ID"
          v-if="currentWithdrawal.adminId"
        >
          {{ currentWithdrawal.adminId }}
        </el-descriptions-item>
        <el-descriptions-item
          label="审核备注"
          :span="2"
          v-if="currentWithdrawal.note"
        >
          {{ currentWithdrawal.note }}
        </el-descriptions-item>
      </el-descriptions>
      <span
        slot="footer"
        class="dialog-footer"
      >
        <el-button @click="detailVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <!-- 审核对话框 -->
    <el-dialog
      title="处理提现申请"
      :visible.sync="reviewVisible"
      width="50%"
    >
      <el-form label-width="100px">
        <el-form-item label="用户ID">
          <span>{{ reviewForm.user_id }}</span>
        </el-form-item>
        <el-form-item label="申请金额">
          <span>{{ reviewForm.amount | currency }}</span>
        </el-form-item>
        <el-form-item label="处理操作">
          <el-radio-group v-model="reviewForm.status">
            <el-radio label="APPROVED">通过</el-radio>
            <el-radio label="REJECTED">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="审核备注">
          <el-input
            type="textarea"
            :rows="4"
            placeholder="请输入审核备注"
            v-model="reviewForm.note"
          ></el-input>
        </el-form-item>
      </el-form>
      <span
        slot="footer"
        class="dialog-footer"
      >
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" @click="submitReview">确定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { getWithdrawals, updateWithdrawalStatus } from "@/api/withdrawals.js";
import Qs from "qs"

export default {
  name: "WithdrawalManage",
  data() {
    return {
      query: {
        user_id: "",
        status: "",
        start_time: "",
        end_time: "",
        page: 1,
        size: 15,
      },
      dateRange: [],

      tableData: [],
      total: 0,
      statusOptions: [
        { value: "PENDING", label: "待处理" },
        { value: "APPROVED", label: "已通过" },
        { value: "REJECTED", label: "已驳回" },
      ],
      currentWithdrawal: {},
      detailVisible: false,
      reviewVisible: false,
      reviewForm: {
        id: "",
        user_id: "",
        amount: 0,
        status: "APPROVED",
        note: ""
      }
    };
  },

  filters: {
    currency(value) {
      if (!value) return "¥0.00";
      return `¥${parseFloat(value).toFixed(2)}`;
    }
  },

  created() {
    this.getData();
  },
  
  computed: {
    hasCheckPermission() {
      const perms = JSON.parse(sessionStorage.getItem("spba-perms") || "[]");
      return perms.includes("withdrawal:check");
    },
  },
  
  methods: {
    // 加载提现记录
    getData() {
      getWithdrawals(this.query).then((res) => {
        if (res.code === 200) {
          this.tableData = res.data.records;
          this.total = res.data.total;
        }
      });
    },

    // 搜索
    handleSearch() {
      if (this.dateRange && this.dateRange.length === 2) {
        this.query.start_time = this.dateRange[0];
        this.query.end_time = this.dateRange[1];
      } else {
        this.query.start_time = "";
        this.query.end_time = "";
      }
      this.query.page = 1;
      this.getData();
    },

    // 重置
    handleReset() {
      this.query = {
        user_id: "",
        status: "",
        start_time: "",
        end_time: "",
        page: 1,
        size: 15,
      };
      this.dateRange = [];
      this.getData();
    },

    // 分页
    handlePageChange(val) {
      this.query.page = val;
      this.getData();
    },

    // 查看详情
    handleDetail(row) {
      this.currentWithdrawal = { ...row };
      this.detailVisible = true;
    },

    // 审核操作
    handleReview(row, status) {
      this.reviewForm = {
        id: row.id,
        user_id: row.userId,
        amount: row.amount,
        status: status,
        note: row.note || ""
      };
      this.reviewVisible = true;
    },
    
    // 提交审核
    submitReview() {
      this.$confirm(`确定要${this.reviewForm.status === 'APPROVED' ? "通过" : "驳回"}该提现申请吗?`, "提示", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning",
      }).then(() => {
        updateWithdrawalStatus(Qs.stringify(this.reviewForm)).then((res) => {
          if (res.code === 200) {
            this.$message.success("操作成功");
            this.reviewVisible = false;
            this.getData();
          }
        });
      });
    },

    // 获取状态文本
    getStatusText(status) {
      const option = this.statusOptions.find((item) => item.value === status);
      return option ? option.label : "未知状态";
    },

    // 获取状态标签类型
    getStatusTagType(status) {
      switch (status) {
        case "PENDING":
          return "warning";
        case "APPROVED":
          return "success";
        case "REJECTED":
          return "danger";
        default:
          return "info";
      }
    },
  },
};
</script>

<style scoped>
.handle-box {
  margin-bottom: 20px;
}

.handle-input {
  width: 200px;
  display: inline-block;
}

.table {
  width: 100%;
  font-size: 14px;
}

.mr10 {
  margin-right: 10px;
}

.pagination {
  margin-top: 20px;
  text-align: right;
}
</style>