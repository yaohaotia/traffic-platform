<template>
  <div class="violation-types-container">
    <el-card class="box-card">
      <div
        slot="header"
        class="clearfix"
      >
        <span>违章类型管理</span>
        <el-button
          style="float: right; padding: 3px 0"
          type="text"
          icon="el-icon-refresh"
          @click="fetchViolationTypes"
        >
          刷新
        </el-button>
      </div>

      <el-table
        :data="violationTypes"
        style="width: 100%"
        border
        stripe
        v-loading="loading"
      >

        <el-table-column
          prop="name"
          label="类型名称"
          width="150"
        >
        </el-table-column>

        <el-table-column
          prop="description"
          label="类型说明"
        >
        </el-table-column>

        <el-table-column
          prop="rewardAmount"
          label="奖励金额"
          width="120"
          align="right"
        >
          <template slot-scope="scope">
            ¥{{ scope.row.rewardAmount.toFixed(2) }}
          </template>
        </el-table-column>

        <el-table-column
          prop="isActive"
          label="状态"
          width="120"
          align="center"
        >
          <template slot-scope="scope">
            <el-switch
              v-model="scope.row.isActive"
              active-color="#13ce66"
              inactive-color="#ff4949"
              @change="handleToggleStatus(scope.row)"
            ></el-switch>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="120"
          align="center"
        >
          <template slot-scope="scope">
            <el-button
              size="mini"
              @click="handleEditReward(scope.row)"
              :disabled="!scope.row.isActive"
            >
              设置金额
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 设置金额弹窗 -->
    <el-dialog
      title="设置奖励金额"
      :visible.sync="dialogVisible"
      width="30%"
    >
      <el-form
        :model="currentType"
        label-width="120px"
      >
        <el-form-item label="类型名称">
          <span>{{ currentType.name }}</span>
        </el-form-item>

        <el-form-item label="当前金额">
          <span>¥{{ currentType.rewardAmount.toFixed(2) }}</span>
        </el-form-item>

        <el-form-item label="新金额">
          <el-input-number
            v-model="currentType.newRewardAmount"
            :precision="2"
            :step="1"
            :min="0"
            :max="10000"
          ></el-input-number>
        </el-form-item>
      </el-form>

      <span
        slot="footer"
        class="dialog-footer"
      >
        <el-button @click="dialogVisible = false">取 消</el-button>
        <el-button
          type="primary"
          @click="confirmRewardChange"
          :loading="updating"
        >
          确 定
        </el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { getViolationTypes } from "../../api/report";
import { changeViolation, changeReward } from "../../api/violation";

export default {
  name: "ViolationTypes",
  data() {
    return {
      loading: false,
      updating: false,
      violationTypes: [],
      dialogVisible: false,
      currentType: {
        id: null,
        name: "",
        rewardAmount: 0,
        newRewardAmount: 0,
      },
    };
  },
  methods: {
    // 获取违章类型列表
    async fetchViolationTypes() {
      this.loading = true;
      try {
        const response = await getViolationTypes(0);
        this.violationTypes = response.data;
      } catch (error) {
        console.error("获取违章类型失败:", error);
        this.$message.error("获取违章类型失败");
      } finally {
        this.loading = false;
      }
    },

    // 切换激活状态
    async handleToggleStatus(row) {
      const newStatus = row.isActive;
      try {
        await changeViolation(newStatus, row.id);
        if (newStatus) this.$message.success(`已启用 ${row.name}`);
        else this.$message.info(`已禁用 ${row.name}`);
      } catch (error) {
        console.error("更新状态失败:", error);
        this.$message.error("更新状态失败");
      }
    },

    // 打开设置金额对话框
    handleEditReward(row) {
      this.currentType = {
        id: row.id,
        name: row.name,
        rewardAmount: row.rewardAmount,
        newRewardAmount: row.rewardAmount,
      };
      this.dialogVisible = true;
    },

    // 确认修改金额
    async confirmRewardChange() {
      if (this.currentType.rewardAmount === this.currentType.newRewardAmount) {
        this.dialogVisible = false;
        return;
      }

      this.updating = true;
      try {
        await changeReward(this.currentType.newRewardAmount, this.currentType.id);

        // 更新本地数据
        const index = this.violationTypes.findIndex(
          (item) => item.id === this.currentType.id
        );
        if (index !== -1) {
          this.violationTypes[index].rewardAmount =
            this.currentType.newRewardAmount;
        }

        this.$message.success("奖励金额更新成功");
        this.dialogVisible = false;
      } catch (error) {
        console.error("更新奖励金额失败:", error);
        this.$message.error("更新奖励金额失败");
      } finally {
        this.updating = false;
      }
    },
  },
  created() {
    this.fetchViolationTypes();
  },
};
</script>

<style scoped>
.violation-types-container {
  padding: 20px;
}

.box-card {
  margin-bottom: 20px;
}

.toggle-switch {
  position: relative;
  display: inline-block;
  width: 60px;
  height: 30px;
  background-color: #dcdfe6;
  border-radius: 15px;
  cursor: pointer;
  transition: background-color 0.3s;
}

.toggle-switch.is-active {
  background-color: #67c23a;
}

.toggle-switch-handle {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 24px;
  height: 24px;
  background-color: #fff;
  border-radius: 50%;
  transition: transform 0.3s;
}

.toggle-switch.is-active .toggle-switch-handle {
  transform: translateX(30px);
}

.clearfix:before,
.clearfix:after {
  display: table;
  content: "";
}
.clearfix:after {
  clear: both;
}
</style>