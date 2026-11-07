<!-- <template>
  <div class="change-password">
    <el-empty description="暂未开发，敬请期待......"></el-empty>
  </div>
</template>
<script>
export default {
  name: "changePassword"
}
</script> -->

<template>
  <div class="change-password-container">
    <el-card class="change-password-card">

      <el-form
        ref="passwordForm"
        :model="passwordForm"
        :rules="rules"
        label-width="120px"
        @submit.native.prevent="submitForm"
      >
        <el-form-item
          label="原始密码"
          prop="oldPassword"
        >
          <el-input
            v-model="passwordForm.oldPassword"
            type="password"
            placeholder="请输入原始密码"
            show-password
          ></el-input>
        </el-form-item>

        <el-form-item
          label="新密码"
          prop="newPassword"
        >
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            placeholder="请输入新密码"
            show-password
          ></el-input>
        </el-form-item>

        <el-form-item
          label="确认新密码"
          prop="confirmPassword"
        >
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            show-password
          ></el-input>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            @click="submitForm"
          >提交</el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import Qs from "qs";
import { changePwd } from "../../api/admin";
export default {
  name: "changePassword",
  data() {
    // 自定义验证规则 - 确认密码必须与新密码一致
    const validateConfirmPassword = (rule, value, callback) => {
      if (value !== this.passwordForm.newPassword) {
        callback(new Error("两次输入的密码不一致!"));
      } else {
        callback();
      }
    };

    // 自定义验证规则 - 新密码不能与旧密码相同
    const validateNewPassword = (rule, value, callback) => {
      if (value === this.passwordForm.oldPassword) {
        callback(new Error("新密码不能与旧密码相同!"));
      } else {
        callback();
      }
    };

    return {
      passwordForm: {
        oldPassword: "",
        newPassword: "",
        confirmPassword: "",
      },
      rules: {
        oldPassword: [
          { required: true, message: "请输入原始密码", trigger: "blur" },
          {
            min: 6,
            max: 20,
            message: "密码长度在6到20个字符",
            trigger: "blur",
          },
        ],
        newPassword: [
          { required: true, message: "请输入新密码", trigger: "blur" },
          {
            min: 6,
            max: 20,
            message: "密码长度在6到20个字符",
            trigger: "blur",
          },
          { validator: validateNewPassword, trigger: "blur" },
        ],
        confirmPassword: [
          { required: true, message: "请再次输入新密码", trigger: "blur" },
          { validator: validateConfirmPassword, trigger: "blur" },
        ],
      },
    };
  },
  methods: {
    submitForm() {
      this.$refs.passwordForm.validate((valid) => {
        if (valid) {
          // 这里可以调用修改密码的API
          changePwd(Qs.stringify(this.passwordForm)).then((response) => {
            if (response.code == 200) {
              this.$emit('on-success');
              this.$message.success("密码修改成功！");
              this.resetForm();
            } else {
              this.$message.error(response.message|| "密码修改失败");
              
            }

            // 可以根据需要跳转到其他页面或执行其他操作
          });
        } else {
          this.$message.warning("请正确填写表单");
          return false;
        }
      });
    },
    resetForm() {
      this.$refs.passwordForm.resetFields();
    },
  },
};
</script>

<style scoped>
.change-password-container {
  display: flex;
  justify-content: center;
  align-items: center;

  background-color: #f5f7fa;
}

.change-password-card {
  width: 500px;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
}

.clearfix {
  font-size: 18px;
  font-weight: bold;
  color: #303133;
}

.el-form {
  padding: 20px;
}

.el-button {
  margin-right: 15px;
}
</style>