# 第三方组件与模型

本仓库集成第三方框架、前端依赖及模型，不改变其版权与原有许可；本仓库没有为这些内容重新授予统一许可证。

- XXL-JOB 3.4.2 建表 SQL 来自上游项目。原 SQL 与 GPLv3 许可证分别保留在 `traffic-platform/deploy/vendor/xxl-job-3.4.2.sql`、`XXL-JOB-LICENSE`。初始化脚本在本机生成专用任务与随机凭据，不修改已保留的上游 SQL。
- PatchCraft/RPTC 网络、预处理和 `weights/RPTC.pth` 沿用原项目已有文件。本次新增版本化上传协议、服务封装与业务接入，没有宣称原创模型、重新训练或检测准确率提升。原工程未附带单独的模型许可文件；进一步使用或分发时应核对原作者授权。
- 原前端与其静态资源作为既有项目的一部分保留，依赖版本见 `package.json`、`package-lock.json`；后端依赖见 `pom.xml`，Python 模型依赖见 `service/requirements-cpu-windows.txt`。各依赖适用自身许可证。
