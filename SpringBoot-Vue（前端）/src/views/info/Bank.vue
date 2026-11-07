<template>
  <div class="bank-account-container">
    <!-- 标题和操作按钮 -->
    <div class="header">
      <h2><i class="el-icon-bank-card"></i> 我的银行卡</h2>
      <el-button
        type="primary"
        @click="showAddDialog"
        icon="el-icon-plus"
      >添加银行卡</el-button>
    </div>

    <!-- 银行卡列表 -->
    <el-table
      :data="bankAccounts"
      border
      style="width: 100%"
      v-loading="loading"
      empty-text="暂无银行卡信息，请添加"
    >
      <el-table-column
        prop="bankName"
        label="银行名称"
        width="180"
      >
      </el-table-column>
      <el-table-column
        prop="cardNumber"
        label="卡号"
        width="300"
      >
        <template slot-scope="scope">
          **** **** **** {{ scope.row.cardNumber.slice(-4) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="cardType"
        label="卡片类型"
        width="120"
      >
        <template slot-scope="scope">
          <el-tag :type="scope.row.cardType === '储蓄卡' ? '' : 'success'">
            {{ scope.row.cardType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="isDefault"
        label="默认卡"
        width="100"
      >
        <template slot-scope="scope">
          <el-tag
            v-if="scope.row.isDefault"
            type="success"
          >是</el-tag>
          <el-tag
            v-else
            type="info"
          >否</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="320"
      >
        <template slot-scope="scope">

          <el-button
            size="mini"
            @click="handleEdit(scope.row)"
          >
            编辑
          </el-button>

          <el-button
            size="mini"
            type="danger"
            @click="handleDelete(scope.row)"
            :disabled="scope.row.isDefault"
          >
            删除
          </el-button>
          <el-button
            size="mini"
            @click="handleSetDefault(scope.row)"
            
            
          >
            <span v-if="!scope.row.isDefault">设为默认</span>
            <span v-else>取消默认</span>
          </el-button>
        </template>

      </el-table-column>
    </el-table>

    <!-- 添加/编辑银行卡对话框 -->
    <el-dialog
      :title="dialogTitle"
      :visible.sync="dialogVisible"
      width="500px"
      @close="resetForm"
    >
      <el-form
        :model="form"
        :rules="rules"
        ref="bankForm"
        label-width="100px"
      >
        <el-form-item
          label="银行名称"
          prop="bankName"
        >
          <el-select
            v-model="form.bankName"
            placeholder="请选择银行"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="bank in bankList"
              :key="bank.code"
              :label="bank.name"
              :value="bank.name"
            >
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item
          label="银行卡号"
          prop="cardNumber"
        >
          <el-input
            v-model="form.cardNumber"
            placeholder="请输入银行卡号"
            maxlength="19"
            minlength="16"
            @input="formatCardNumber"
          >
            <!-- <template slot="append">
              <i class="el-icon-folder"></i>
            </template> -->
          </el-input>
        </el-form-item>
        <el-form-item
          label="卡片类型"
          prop="cardType"
        >
          <el-radio-group v-model="form.cardType">
            <el-radio label="储蓄卡"></el-radio>
            <el-radio label="信用卡"></el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item
          label="开户姓名"
          prop="accountName"
        >
          <el-input
            v-model="form.accountName"
            placeholder="请输入开户姓名"
          >
          </el-input>
        </el-form-item>
        <el-form-item
          label="开户支行"
          prop="branchName"
        >
          <el-input
            v-model="form.branchName"
            placeholder="请输入开户支行"
          >
          </el-input>
        </el-form-item>
        <!-- <el-form-item
          label="设为默认"
          prop="isDefault"
        >
          <el-switch v-model="form.isDefault"></el-switch>
        </el-form-item> -->
      </el-form>
      <span
        slot="footer"
        class="dialog-footer"
      >
        <el-button @click="dialogVisible = false">取 消</el-button>
        <el-button
          type="primary"
          @click="submitForm"
          :loading="submitting"
        >确 定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  getBankAccounts,
  addBankAccount,
  updateBankAccount,
  deleteBankAccount,
  setDefaultBankAccount,
} from "@/api/bank";

// 银行列表数据
const bankList = [
  { code: "ICBC", name: "中国工商银行" },
  { code: "ABC", name: "中国农业银行" },
  { code: "BOC", name: "中国银行" },
  { code: "CCB", name: "中国建设银行" },
  { code: "CMB", name: "招商银行" },
  { code: "COMM", name: "交通银行" },
  { code: "SPDB", name: "上海浦东发展银行" },
  { code: "CIB", name: "兴业银行" },
  { code: "CMBC", name: "中国民生银行" },
  { code: "PSBC", name: "中国邮政储蓄银行" },
  { code: "CITIC", name: "中信银行" },
  { code: "HXB", name: "华夏银行" },
  { code: "GDB", name: "广东发展银行" },
  { code: "CEB", name: "中国光大银行" },
  { code: "PAB", name: "平安银行" },
];

export default {
  name: "BankAccountManagement",
  data() {
    // 银行卡号验证
    const validateCardNumber = (rule, value, callback) => {
      if (!value) {
        return callback(new Error("请输入银行卡号"));
      }
      const cleanValue = value.replace(/\s+/g, "");
      if (!/^\d{16,19}$/.test(cleanValue)) {
        return callback(new Error("银行卡号应为16-19位数字"));
      }
      callback();
    };

    return {
      loading: false,
      bankAccounts: [],
      bankList,
      dialogVisible: false,
      dialogTitle: "添加银行卡",
      submitting: false,
      currentId: null,
      form: {
        bankName: "",
        cardNumber: "",
        cardType: "储蓄卡",
        accountName: "",
        branchName: "",
        isDefault: false,
      },
      rules: {
        bankName: [{ required: true, message: "请选择银行", trigger: "blur" }],
        cardNumber: [{ validator: validateCardNumber, trigger: "blur" }],
        cardType: [
          { required: true, message: "请选择卡片类型", trigger: "change" },
        ],
        accountName: [
          { required: true, message: "请输入开户姓名", trigger: "blur" },
          { min: 2, max: 10, message: "长度在2到10个字符", trigger: "blur" },
        ],
      },
    };
  },
  created() {
    this.fetchBankAccounts();
  },
  methods: {
    // 获取银行账户列表
    async fetchBankAccounts() {
      this.loading = true;
      try {
        const res = await getBankAccounts();
        if (res.code == 200) {
          this.bankAccounts = res.data;
        } else {
          this.$message.error(res.message);
        }
      } catch (error) {
        this.$message.error("获取银行卡列表失败: " + error.message);
      } finally {
        this.loading = false;
      }
    },

    // 显示添加对话框
    showAddDialog() {
      this.dialogTitle = "添加银行卡";
      this.currentId = null;
      this.dialogVisible = true;
    },

    // 格式化银行卡号显示
    formatCardNumber() {
      
      // 移除所有非数字字符
      let value = this.form.cardNumber.replace(/\D/g, "");
      // 每4位添加一个空格
      value = value.replace(/(\d{4})(?=\d)/g, "$1 ");
      this.form.cardNumber = value;
    },

    // 提交表单
    submitForm() {
      this.$refs.bankForm.validate(async (valid) => {
        if (valid) {
          this.submitting = true;
          try {
            const formData = {
              ...this.form,
              cardNumber: this.form.cardNumber.replace(/\s+/g, ""), // 去除空格
            };

            if (this.currentId) {
              // 编辑
              const res = await updateBankAccount(this.currentId, formData);

              if (res.code == 200) {
                this.$message.success("银行卡信息更新成功");
              } else {
                this.$message.error(res.message);
              }
            } else {
              // 新增
              const res = await addBankAccount(formData);
              if (res.code == 200) {
                this.$message.success("银行卡添加成功");
              } else {
                this.$message.error(res.message);
              }
            }

            this.dialogVisible = false;
            this.fetchBankAccounts(); // 刷新列表
          } catch (error) {
            this.$message.error("操作失败: " + error.message);
          } finally {
            this.submitting = false;
          }
        }
      });
    },

    // 重置表单
    resetForm() {
      this.$refs.bankForm.resetFields();
      this.currentId = null;
    },

    // 编辑银行卡
    handleEdit(row) {
      this.dialogTitle = "编辑银行卡";
      this.currentId = row.id;
      this.dialogVisible = true;

      // 填充表单数据
      this.$nextTick(() => {
        this.form = {
          bankName: row.bankName,
          cardNumber: row.cardNumber.replace(/(\d{4})(?=\d)/g, "$1 "),
          cardType: row.cardType,
          accountName: row.accountName,
          branchName: row.branchName || "",
          isDefault: row.isDefault,
        };
      });
    },

    // 删除银行卡
    handleDelete(row) {
      this.$confirm("确定要删除这张银行卡吗?", "提示", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning",
      })
        .then(async () => {
          try {
            const res = await deleteBankAccount(row.id);
            if (res.code == 200) {
              this.$message.success("删除成功");
              this.fetchBankAccounts();
            } else {
              this.$message.error(res.message);
            }
          } catch (error) {
            this.$message.error("删除失败: " + error.message);
          }
        })
        .catch(() => {
          this.$message.info("已取消删除");
        });
    },

    // 设为默认银行卡
    async handleSetDefault(row) {
      try {
        const res = await setDefaultBankAccount(row.id);
        if (res.code == 200) {
          this.$message.success("修改状态成功");
          this.fetchBankAccounts();
        } else {
          this.$message.error(res.message);
        }
      } catch (error) {
        this.$message.error("设置失败: " + error.message);
      }
    },
  },
};
</script>

<style scoped>
.bank-account-container {
  padding: 20px;
  background: #fff;
  border-radius: 4px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.header h2 {
  color: #303133;
  font-size: 20px;
}

.header h2 i {
  margin-right: 10px;
  color: #409eff;
}

.el-table {
  margin-top: 20px;
}

.el-tag {
  margin-right: 5px;
}

/* 响应式调整 */
@media (max-width: 768px) {
  .header {
    flex-direction: column;
    align-items: flex-start;
  }

  .header .el-button {
    margin-top: 10px;
  }

  /deep/ .el-table__body-wrapper {
    overflow-x: auto;
  }
}
</style>