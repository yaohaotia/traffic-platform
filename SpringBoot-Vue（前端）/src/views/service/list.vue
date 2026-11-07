<template>
  <div class="report-manage">
    <div class="container">
      <div class="handle-box">
        <el-input
          v-model="query.location"
          placeholder="请输入地点"
          class="handle-input mr10"
        ></el-input>
        <el-select
          v-model="query.violation_type_id"
          placeholder="请选择违章类型"
          clearable
          class="mr10"
          style="width: 200px"
        >
          <el-option
            v-for="item in violationTypes"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
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
          label="举报图片"
          width="140"
          align="center"
        >
          <template v-slot="scope">
            <el-image
              style="width: 100px; height: 60px"
              :src="scope.row.imageUrl"
              :preview-src-list="[scope.row.imageUrl,]"
              fit="cover"
            ></el-image>
          </template>
        </el-table-column>
        <el-table-column
          prop="location"
          label="违章地点"
          width="150"
          align="center"
        ></el-table-column>
        <el-table-column
          label="违章类型"
          width="120"
          align="center"
        >
          <template slot-scope="scope">
            {{ scope.row.violationTypeName }}
          </template>
        </el-table-column>
        <el-table-column
          label="举报状态"
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
          prop="captureTime"
          label="拍摄时间"
          width="160"
          align="center"
        ></el-table-column>
        <el-table-column
          prop="createdTime"
          label="举报时间"
          width="160"
          align="center"
        ></el-table-column>
        <el-table-column
          prop="reward"
          label="奖励金额"
          width="100"
          align="center"
        >
          <template slot-scope="scope">
            {{ scope.row.reward }}元
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="180"
          align="center"
        >
          <template slot-scope="scope">
            <!-- First Row (Center the "详情" button) -->
            <div style="display: flex; justify-content: center; width: 100%;">
              <el-button
                type="primary"
                size="mini"
                icon="el-icon-view"
                @click="handleDetail(scope.row)"
                style="margin-right: 10px;"
              >详情</el-button>
            </div>

            <!-- Second Row (For the "通过" and "驳回" buttons) -->
            <div style="display: flex; justify-content: start; margin-top: 10px;">
              <el-button
                v-if="scope.row.status === 2 && hasCheckPermission"
                type="success"
                size="mini"
                icon="el-icon-check"
                @click="handleReview(scope.row, 3)"
                style="margin-right: 10px;"
              >通过</el-button>

              <el-button
                v-if="scope.row.status === 2 && hasCheckPermission"
                type="danger"
                size="mini"
                icon="el-icon-close"
                @click="handleReview(scope.row, 4)"
              >驳回</el-button>
            </div>
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
      title="举报详情"
      :visible.sync="detailVisible"
      width="70%"
    >
      <el-descriptions
        :column="2"
        border
      >
        <el-descriptions-item label="举报ID">{{ currentReport.id }}</el-descriptions-item>
        <el-descriptions-item label="举报人">{{ currentReport.userName }}</el-descriptions-item>
        <el-descriptions-item label="违章地点">{{ currentReport.location }}</el-descriptions-item>
        <el-descriptions-item label="违章类型">
          {{ getViolationTypeName(currentReport.violationTypeId) }}
        </el-descriptions-item>
        <el-descriptions-item label="拍摄时间">{{ currentReport.captureTime }}</el-descriptions-item>
        <el-descriptions-item label="举报时间">{{ currentReport.createdTime }}</el-descriptions-item>
        <el-descriptions-item label="举报状态">
          <el-tag :type="getStatusTagType(currentReport.status)">
            {{ getStatusText(currentReport.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item
          label="审核员id"
          v-if="currentReport.inspectorId"
        >
          {{ currentReport.inspectorId }}
        </el-descriptions-item>
        <el-descriptions-item
          label="审核时间"
          v-if="currentReport.reviewedTime"
        >
          {{ currentReport.reviewedTime }}
        </el-descriptions-item>
        <el-descriptions-item label="奖励金额">{{ currentReport.reward }}元</el-descriptions-item>
        <el-descriptions-item
          label="举报说明"
          :span="2"
        >
          {{ currentReport.description }}
        </el-descriptions-item>
        <el-descriptions-item
          label="违章图片"
          :span="2"
        >
          <el-image
            style="width: 100%; max-width: 500px"
            :src="currentReport.imageUrl"
            :preview-src-list="[currentReport.imageUrl]"
            fit="contain"
          ></el-image>
        </el-descriptions-item>
      </el-descriptions>
      <span
        slot="footer"
        class="dialog-footer"
      >
        <el-button @click="detailVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  getReports,
  updateReportStatus,
  getViolationTypes,
} from "../../api/report";

export default {
  name: "ReportManage",
  data() {
    return {
      query: {
        location: "",
        violation_type_id: "",
        status: "",
        start_time: "",
        end_time: "",
        page: 1,
        size: 15,
      },
      dateRange: [],

      tableData: [],
      total: 0,
      violationTypes: [],
      statusOptions: [
        { value: 0, label: "未检测" },
        { value: 1, label: "检测为AI生成的图片" },
        { value: 2, label: "待人工审核" },
        { value: 3, label: "确认违章" },
        { value: 4, label: "非违章" },
      ],
      currentReport: {},
      detailVisible: false,
    };
  },

  created() {
    this.getData();
    this.loadViolationTypes();
  },
  computed: {
    hasCheckPermission() {
      const perms = JSON.parse(sessionStorage.getItem("spba-perms") || "[]");
      for (const item of perms) {
        if (item === "report:check") {
          return true;
        }
      }
      return false;
    },
  },
  methods: {
    // 加载举报记录
    getData() {
      getReports(this.query).then((res) => {
        if (res.code === 200) {
          this.tableData = res.data.records;
          this.total = res.data.total;
          console.log("数据加载成功", this.tableData); // 辅助调试
        }
      });
    },

    // 加载违章类型
    loadViolationTypes() {
      getViolationTypes(1).then((res) => {
        if (res.code === 200) {
          this.violationTypes = res.data;
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
        location: "",
        violation_type_id: "",
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
      this.currentReport = { ...row };
      this.detailVisible = true;
    },

    // 审核操作
    handleReview(row, status) {
      this.$confirm(`确定要${status === 3 ? "通过" : "驳回"}吗?`, "提示", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",

        type: "warning",
      }).then(() => {
        updateReportStatus({ id: row.id, status: status }).then((res) => {
          if (res.code === 200) {
            this.$message.success("操作成功");
            this.getData();
          }
        });
      });
    },

    // 获取违章类型名称
    getViolationTypeName(id) {
      const type = this.violationTypes.find((item) => item.id === id);
      return type ? type.name : "未知类型";
    },

    // 获取状态文本
    getStatusText(status) {
      const option = this.statusOptions.find((item) => item.value === status);
      return option ? option.label : "未知状态";
    },

    // 获取状态标签类型
    getStatusTagType(status) {
      switch (status) {
        case 0:
          return "info";
        case 1:
          return "";
        case 2:
          return "warning";
        case 3:
          return "success";
        case 4:
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
/* .el-image-viewer__actions .el-image-viewer__next {
  display: none !important;
} */
</style>