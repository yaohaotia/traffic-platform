<template>
  <div
    class="login-wrap"
    :style="bgStyle"
  >
    <div class="ms-login">
      <div class="ms-title">
        <strong>Transport</strong>
        <div class="ms-subtitle">{{ showRegister ? '用户注册' : '用户登录' }}</div>
      </div>

      <!-- 登录表单 -->
      <transition
        name="fade"
        mode="out-in"
      >
        <el-form
          v-if="!showRegister"
          :model="param"
          :rules="rules"
          ref="login"
          label-width="0px"
          class="ms-content"
        >
          <el-form-item prop="username">
            <el-input
              v-model="param.username"
              placeholder="请输入账号"
              prefix-icon="el-icon-user"
            />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              type="password"
              placeholder="请输入密码"
              v-model="param.password"
              @keyup.enter.native="submitForm()"
              prefix-icon="el-icon-lock"
            />
          </el-form-item>
          <div class="login-btn">
            <el-button
              type="primary"
              @click="submitForm()"
              :loading="loading"
              class="full-width-btn"
            >登 录</el-button>
            <div class="switch-text">
              还没有账号？<span @click="toRegister()">立即注册</span>
            </div>
          </div>
        </el-form>

        <!-- 注册表单 -->
        <el-form
          v-else
          :model="registerForm"
          :rules="registerRules"
          ref="register"
          label-width="0px"
          class="ms-content register-form"
        >
          <el-form-item prop="username">
            <el-input
              v-model="registerForm.username"
              placeholder="请输入账号(4-16位字符)"
              prefix-icon="el-icon-user"
            />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              type="password"
              placeholder="请输入密码(6-20位字符)"
              v-model="registerForm.password"
              prefix-icon="el-icon-lock"
            />
          </el-form-item>
          <el-form-item prop="confirmPassword">
            <el-input
              type="password"
              placeholder="请确认密码"
              v-model="registerForm.confirmPassword"
              @keyup.enter.native="handleRegister()"
              prefix-icon="el-icon-lock"
            />
          </el-form-item>
          <div class="login-btn">
            <el-button
              type="success"
              @click="handleRegister()"
              :loading="loading"
              class="full-width-btn"
            >注 册</el-button>
            <div class="switch-text">
              已有账号？<span @click="showRegister = false">立即登录</span>
            </div>
          </div>
        </el-form>
      </transition>
    </div>
  </div>
</template>

<script>
import Qs from "qs";
import { login, register } from "../../api/common";

export default {
  name: "LoginPage",
  data() {
    const validateConfirmPassword = (rule, value, callback) => {
      if (value !== this.registerForm.password) {
        callback(new Error("两次输入的密码不一致"));
      } else {
        callback();
      }
    };

    return {
      param: {
        username: "",
        password: "",
      },
      registerForm: {
        username: "",
        password: "",
        confirmPassword: "",
      },
      showRegister: false,
      loading: false,
      bgStyle: {
        // backgroundImage:
        //   "url(https://source.unsplash.com/random/1920x1080/?transport)",
        backgroundImage: `url(${require("@/assets/image/transport.jpg")})`,
        backgroundSize: "cover",
        backgroundPosition: "center",
        backgroundRepeat: "no-repeat",
      },
      rules: {
        username: [{ required: true, message: "请输入账号", trigger: "blur" }],
        password: [{ required: true, message: "请输入密码", trigger: "blur" }],
      },
      registerRules: {
        username: [
          { required: true, message: "请输入账号", trigger: "blur" },
          { min: 4, max: 16, message: "长度在4到16个字符", trigger: "blur" },
        ],
        password: [
          { required: true, message: "请输入密码", trigger: "blur" },
          { min: 6, max: 20, message: "长度在6到20个字符", trigger: "blur" },
        ],
        confirmPassword: [
          { required: true, message: "请确认密码", trigger: "blur" },
          { validator: validateConfirmPassword, trigger: "blur" },
        ],
      },
    };
  },
  methods: {
    toRegister() {
      this.showRegister = true;
      this.$nextTick(() => {
        this.$refs.register && this.$refs.register.clearValidate();
      });
    },
    submitForm() {
      this.$refs.login.validate((valid) => {
        if (valid) {
          this.handleLogin();
        }
      });
    },
    async handleLogin() {
      this.loading = true;
      try {
        const res = await login(Qs.stringify(this.param));
        this.handleLoginSuccess(res);
      } catch (error) {
        console.error("登录失败:", error);
        this.handleLoginError(error);
      } finally {
        this.loading = false;
      }
    },

    handleLoginSuccess(result) {
      if (result.code === 200) {
        if (result.data.perms.length === 0) {
          this.$message.error("暂无权限");
          return;
        }
        this.saveSessionData(result.data);
        this.redirectAfterLogin(result.data.menu);
      } else {
        this.$message.error(result.message);
      }
    },
    handleLoginError() {
      this.$message.error("登录失败，请重试");
    },
    saveSessionData(data) {
      sessionStorage.setItem("spba-username", data.username);
      sessionStorage.setItem("spba-avatar", data.avatar);
      sessionStorage.setItem("spba-token", data.token);
      sessionStorage.setItem("spba-menu", JSON.stringify(data.menu));
      sessionStorage.setItem("spba-perms", JSON.stringify(data.perms));
      sessionStorage.setItem("spba-error-count", data.errLogCount);
    },
    redirectAfterLogin(menu) {
      const path =
        menu[0].children.length === 0 ? menu[0].path : menu[0].children[0].path;
      this.$router.push(`/${path}`);
    },
    async handleRegister() {
      this.$refs.register.validate(async (valid) => {
        if (!valid) return;

        this.loading = true;
        try {
          // 解构去掉 confirmPassword，添加新字段
          const { confirmPassword, ...registerData } = this.registerForm;
          registerData.status = 1;
          registerData.roleIds = "3";

          const res = await register(Qs.stringify(registerData));
          this.handleRegisterSuccess(res);
        } catch (error) {
          this.handleRegisterError(error);
        } finally {
          this.loading = false;
        }
      });
    },
    handleRegisterSuccess(result) {
      if (result.code === 200) {
        this.$message.success("注册成功，请登录");
        this.showRegister = false;

        this.param.username = this.registerForm.username;
        this.param.password = "";
        this.registerForm = {
          username: "",
          password: "",
          confirmPassword: "",
        };

        this.$nextTick(() => {
          this.$refs.login && this.$refs.login.clearValidate();
        });
      } else {
        this.$message.error(result.message);
      }
    },
    handleRegisterError() {
      this.$message.error("注册失败，请重试");
    },
  },
};
</script>

<style lang="scss" scoped>
.login-wrap {
  position: relative;
  width: 100%;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  &::before {
    content: "";
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background-color: rgba(0, 0, 0, 0.5);
    z-index: 0;
  }
}

.ms-login {
  position: relative;
  width: 400px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.9);
  overflow: hidden;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.3);
  z-index: 1;
  transition: all 0.3s ease;

  &:hover {
    transform: translateY(-5px);
    box-shadow: 0 5px 25px rgba(0, 0, 0, 0.4);
  }
}

.ms-title {
  width: 100%;
  padding: 30px 0 10px;
  color: #333;
  text-align: center;
  font-size: 26px;
  font-weight: 500;
  letter-spacing: 2px;

  strong {
    font: italic 1em Georgia, serif;
  }
}

.ms-subtitle {
  font-size: 16px;
  color: #666;
  margin-top: 5px;
  margin-bottom: 20px;
}

.ms-content {
  padding: 30px;

  &.register-form {
    padding-top: 10px;
  }
}

.login-btn {
  text-align: center;
  margin-top: 20px;

  .full-width-btn {
    width: 100%;
    height: 40px;
    font-size: 16px;
    letter-spacing: 2px;
  }
}

.switch-text {
  margin-top: 15px;
  color: #666;
  font-size: 14px;

  span {
    color: #409eff;
    cursor: pointer;
    &:hover {
      text-decoration: underline;
    }
  }
}

/* 过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s, transform 0.3s;
}
.fade-enter,
.fade-leave-to {
  opacity: 0;
  transform: translateX(20px);
}

/* 响应式调整 */
@media (max-width: 768px) {
  .ms-login {
    width: 90%;
    max-width: 350px;
  }

  .ms-content {
    padding: 20px;
  }
}
</style>