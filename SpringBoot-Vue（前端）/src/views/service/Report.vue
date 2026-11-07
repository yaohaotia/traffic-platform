<template>
  <div class="violation-create">
    <el-form
      :model="form"
      :rules="rules"
      ref="createViolation"
      label-width="120px"
    >

      <el-form-item
        prop="image"
        label="选择图片"
      >
        <input
          type="file"
          @change="handleFileChange"
          accept="image/*"
          ref="fileInput"
          style="display: none"
        />
        <el-button
          type="primary"
          @click="triggerFileInput"
          icon="el-icon-upload"
        >选择图片</el-button>

        <div
          class="image-preview-container"
          v-if="imagePreviewUrl"
        >
          <div class="image-preview">
            <img
              :src="imagePreviewUrl"
              alt="预览图片"
            >
            <div class="image-actions">
              <el-button
                type="danger"
                icon="el-icon-delete"
                circle
                size="mini"
                @click="removeImage"
              ></el-button>
            </div>
          </div>
        </div>
      </el-form-item>

      <!-- 地点 -->
      <el-form-item
        prop="location"
        label="违法地点"
      >
        <el-input
          v-model="form.location"
          placeholder="请输入违法地点"
        ></el-input>
      </el-form-item>

      <!-- 描述 -->
      <el-form-item
        prop="description"
        label="违法描述"
      >
        <el-input
          type="textarea"
          v-model="form.description"
          placeholder="请输入违法描述"
        ></el-input>
      </el-form-item>

      <!-- 拍摄时间 -->
      <el-form-item
        prop="capture_time"
        label="拍摄时间"
      >
        <el-date-picker
          v-model="form.capture_time"
          type="datetime"
          placeholder="选择时间"
          style="width: 100%"
        />
      </el-form-item>

      <!-- 违章类型 -->
      <el-form-item
        prop="violation_type_id"
        label="违章类型"
      >
        <el-select
          v-model="form.violation_type_id"
          placeholder="请选择违章类型"
          style="width: 100%"
          :loading="typeLoading"
        >
          <el-option
            v-for="item in violationTypes"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>

      <!-- 提交按钮 -->
      <el-form-item>
        <el-button
          type="primary"
          @click="submitForm"
          :loading="loading"
        >提交举报</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script>
import Qs from "qs";
import { report, getViolationTypes } from "../../api/report";

export default {
  name: "createViolation",
  data() {
    return {
      form: {
        image: null,
        location: "",
        description: "",
        capture_time: "",
        violation_type_id: "",
      },
      rules: {
        image: [{ required: true, message: "请选择图片", trigger: "change" }],
        location: [
          { required: true, message: "请输入违法地点", trigger: "blur" },
        ],
        description: [
          { required: true, message: "请输入违法描述", trigger: "blur" },
        ],
        capture_time: [
          { required: true, message: "请选择拍摄时间", trigger: "change" },
        ],
        violation_type_id: [
          { required: true, message: "请选择违章类型", trigger: "change" },
        ],
      },
      loading: false,
      typeLoading: false,
      violationTypes: [],
      imagePreviewUrl: "",
    };
  },
  created() {
    this.typeLoading = true; // 开始加载前设置为 true
    getViolationTypes(1)
      .then((res) => {
        if (res.code === 200) {
          this.violationTypes = res.data;
        } else {
          this.$message.error("获取违章类型失败");
        }
      })
      .catch((error) => {
        console.error(error);
        this.$message.error("请求出错");
      })
      .finally(() => {
        this.typeLoading = false; // 无论成功失败，最后都关闭 loading
      });
  },
  methods: {
    // handleFileChange(event) {
    //     const file = event.target.files[0];
    //     if (file) {
    //         this.form.image = file;
    //         const reader = new FileReader();
    //         reader.onload = (e) => {
    //             this.imagePreviewUrl = e.target.result;
    //         };
    //         reader.readAsDataURL(file);
    //     }
    // },
    triggerFileInput() {
      this.$refs.fileInput.click();
    },

    // 删除图片
    removeImage() {
      this.form.image = null;
      this.imagePreviewUrl = "";
      this.$refs.fileInput.value = ""; // 清空文件输入
    },

    // 文件变更处理
    handleFileChange(event) {
      const file = event.target.files[0];
      if (file) {
        // 验证文件类型
        if (!file.type.match("image.*")) {
          this.$message.error("请选择图片文件");
          return;
        }

        // 验证文件大小 (例如限制5MB)
        if (file.size > 5 * 1024 * 1024) {
          this.$message.error("图片大小不能超过5MB");
          return;
        }

        this.form.image = file;
        const reader = new FileReader();
        reader.onload = (e) => {
          this.imagePreviewUrl = e.target.result;
        };
        reader.readAsDataURL(file);
        // 手动触发表单验证
      this.$refs.createViolation.validateField('image');
      }
    },

    async submitForm() {
      this.$refs.createViolation.validate(async (valid) => {
        if (valid) {
          this.loading = true;

          try {
            // 创建FormData对象处理文件上传
            const formData = new FormData();

            // 处理所有字段，包括图片
            for (const key in this.form) {
              if (key === "capture_time" && this.form[key]) {
                formData.append(key, new Date(this.form[key]).toISOString());
              } else {
                formData.append(key, this.form[key]);
              }
            }

          
            report(formData).then((res) => {
              if (res.code === 200) {
                this.$message.success("提交成功");
                this.form = {
                  image: null,
                  location: "",
                  description: "",
                  capture_time: "",
                  violation_type_id: "",
                };
                // 重置文件输入
                this.imagePreviewUrl = "";
                this.$refs.fileInput.value = "";
                // 重置表单验证状态
                this.$nextTick(() => {
                  this.$refs.createViolation.clearValidate();
                });
              } else {
                this.$message.error(res.message || "提交失败");
              }
            });
          } catch (error) {
            this.$message.error("提交失败..");
          } finally {
            this.loading = false;
          }
        }
      });
    },
  },
};
</script>

<style scoped>
.image-preview-container {
  margin-top: 10px;
}

.image-preview {
  position: relative;
  display: inline-block;
  border: 1px dashed #ddd;
  padding: 5px;
  border-radius: 4px;
}

.image-preview img {
  max-width: 300px;
  max-height: 200px;
  display: block;
}

.image-actions {
  position: absolute;
  top: -10px;
  right: -10px;
}

/* 使删除按钮更明显 */
.el-button--danger.is-circle {
  background: #f56c6c;
  color: white;
}

.el-button--danger.is-circle:hover {
  background: #e64c4c;
}
</style>