# 独立部署验证

此Compose使用全新的traffic-platform-deploy卷，不连接原数据库或traffic-platform-dev测试数据库，不发布MySQL/Redis/MQ端口。应用入口127.0.0.1:28090，XXL调度中心127.0.0.1:29090；不是公网发布。

1. 先完成阶段构建，生成target/traffic-platform-0.1.0-SNAPSHOT.jar。
2. PowerShell7执行`./initialize.ps1`，只在首次创建独立随机凭据和XXL业务维护任务；已存在.env时拒绝覆盖。凭据文件与生成SQL已Git忽略。
3. 在本目录执行`docker compose up -d --build --wait --wait-timeout 180`。
4. 验证`http://127.0.0.1:28090/actuator/health/readiness`及`/api/v1/system/info`。XXL执行器注册和五项维护任务应产生实际成功记录。
5. 初始业务用户仍需显式bootstrap或P8演示初始化；不在镜像内附带默认管理员。调度中心用户traffic.admin，密码仅保存在.env的TRAFFIC_JOB_ADMIN_PASSWORD。

Python模型运行在固定Anaconda解释器所在的宿主机，通过TRAFFIC_MODEL_URL配置；默认访问host.docker.internal:5001。P8已完成本机真实权重和前端联调。首次克隆后需自行构建前端，并将dist-traffic内容复制到本目录web中；web属于构建产物，不纳入Git。完整首次启动步骤见仓库根目录README，不能只凭容器healthy认定模型已启动。

所有应用实例必须共享同一个deployment_evidence卷，使用shared-volume存储适配器；该实现不代表云对象存储。容器应用使用UID10001运行，证据通过受鉴权的ID接口读取，不通过Nginx静态目录公开。

XXL-JOB3.4.2的原始建表SQL及GPLv3许可证保存在vendor，来源https://github.com/xuxueli/xxl-job/tree/3.4.2。初始化脚本保留DDL，去掉示例任务和默认账号，新增本项目任务和随机密码摘要。按原许可证保留上游声明；没有修改或重新发布第三方库源码。

停止此部署可以用本目录的docker compose stop。不要使用down -v清理验证数据，也不要将此Compose命令替换为原环境的全局清理命令。当前部署验证状态以执行记录为准。
