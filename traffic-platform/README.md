# 交通线索智能审核与激励平台

围绕校园/园区交通线索，完成专项征集、证据上传、合成风险筛查、人工审核/独立复核、预算与奖励、通知及追踪。后端、前端与模型服务独立部署，业务仅使用本平台数据。

完整计划和阶段记录见[项目重构计划书](../docs/项目重构计划书.md)、[重构执行记录](../docs/重构执行记录.md)。架构/版本见[架构与技术栈](../docs/架构与技术栈.md)，简历段落见[简历项目描述](../docs/简历项目描述.md)。

## 当前运行入口

- 平台：http://127.0.0.1:28090
- XXL-JOB管理端：http://127.0.0.1:29090
- 本地RPTC模型：127.0.0.1:5001
- 平台演示账号及随机密码：本机Git忽略文件 `deploy/.generated/demo-accounts.json`。kind=user/manager/reviewer/rechecker分别为用户、运营、初审和复核；bootstrap用于初始化。
- 调度管理端账号traffic.admin，随机密码位于Git忽略文件 `deploy/.env` 的TRAFFIC_JOB_ADMIN_PASSWORD。

调度管理员登录密码为20位随机Base64url，兼容XXL-JOB 3.4.2网页的20位上限；其他服务密钥独立生成。已有部署遇到旧48位密码被网页截断的问题时，需同步修复数据库密码摘要与本机配置，不能只截取旧密码。`tools/test-xxl-login.ps1`验证网页长度限制、认证及后台访问。

入口仅发布到本机回环地址。平台不内置固定管理员密码，也不在文档里公开随机凭据。当前完整验收状态以执行记录和test-reports中的结构化证据为准。

## 技术与一致性

Java21、Spring Boot4.1.2、Spring Security7.1.2、JWT/BCrypt、MySQL8、MyBatis-Plus3.5.18、Spring JDBC/Flyway、Redis6.2/Lua、RabbitMQ4.1、Redisson4.8、XXL-JOB3.4.2、Actuator/Micrometer/Prometheus、springdoc/OpenAPI、Docker Compose/Nginx；前端为原Vue2技术栈的独立迁移入口，模型为Python3.12/Flask/PyTorch的原RPTC权重。

- JWT每次验证Redis会话及数据库用户状态/权限版本，刷新令牌原子轮换；注销、停用、权限变更后旧凭据失效。
- 上传校验真实PNG/JPEG、5MiB/2000万像素及内容摘要。通过受鉴权ID接口查看，禁止自审、跨区查看证据；不接受用户传服务器存储路径。
- 报告/检测任务/Outbox同事务；MQ确认成功且无Return才标记SENT。消费业务提交后才ACK，毒消息可靠转存后才删除原投递。
- 检测短事务领取，事务外HTTP推理，提交校验任务版本/token/租约；有限重试、死信、人工重跑及补偿回收。
- 审核终审与预算预留/唯一奖励单同事务。奖励消费者幂等入账，钱包/不可变流水/状态同事务，更正保留原流水并创建补偿。
- Redis看门狗锁协调维护批次；数据库唯一键和条件更新继续保证业务幂等。对账有界游标扫描，发现差异只报告。
- 征集详情使用30秒展示缓存，写操作读取数据库；提交后失效，失效失败由TTL限制展示延迟。

## Windows构建和验收

Java/Maven脚本默认显式使用D:/Service/JDK21及D:/Service/apache-maven-3.6.3-bin/apache-maven-3.6.3。依赖缓存在工作区.cache/maven，不依赖系统默认Java版本。

```powershell
# 专用真实测试基础设施，端口23306/26379/25672
docker compose -f ./dev/compose.yml up -d --wait
# 构建与全部测试
& './tools/build.ps1' -Goals @('verify')
# 最终阶段关卡，要求前序报告和真实部署证据
& './tools/test-stage.ps1' -Stage P8
# 前端生产构建，自动处理Node22与原Webpack4的兼容选项
& './tools/build-web.ps1'
```

P8的EndToEndTest会以固定Anaconda启动测试自有模型进程，需要已安装的项目缓存依赖。不会跳过缺失依赖或用桩替代真实权重。测试只清理traffic_platform_it、自己的Redis会话和随机MQ命名空间。

## 启动模型

所有Python调用固定为 `D:/soft/Anaconda/python.exe`，与Git Bash的 `/d/soft/Anaconda/python` 为同一解释器。模型包隔离在工作区 `.cache/python-model`，不替换Anaconda全局包。

```powershell
# 缺少依赖时安装；不要换解释器
& 'D:/soft/Anaconda/python.exe' -m pip install --target 'D:/Project/java/.cache/python-model' -r '../PatchCraft（算法）/service/requirements-cpu-windows.txt'
# 本地联调入口
& './tools/start-model.ps1'
```

模型版本包含原权重SHA256。真实运行证据为P8-model-summary.json；程序化样本验证运行/确定性/协议，没有进行有标注准确率评测。旧product.py及原权重/网络未被覆盖。

## Docker演示部署

部署说明见[deploy/README.md](deploy/README.md)。独立项目名traffic-platform-deploy，使用全新MySQL/Redis/MQ及持久卷；不连接原数据库或测试库。

首次 `deploy/initialize.ps1` 生成随机凭据及本项目XXL任务，拒绝覆盖既有配置。构建后执行 `tools/test-deployment.ps1`，实际验证Nginx/认证、依赖就绪、XXL注册与五类任务回调、应用重启恢复。

前端生产文件来自 `../SpringBoot-Vue（前端）/dist-traffic`，装入 `deploy/web`。账户/区域/角色、征集、审核、钱包、通知及运营均有页面。当前业务入口为src/traffic，使用本平台独立的身份认证和权限配置。

所有应用副本必须挂同一共享证据卷；prod禁用开发local适配器，显式使用shared-volume。容器应用以UID10001运行。不要删除持久卷来“修复”业务状态或测试失败。

## 接口与实现边界

接口契约见P3-contract.md至P7-contract.md及[接口迁移清单](../docs/接口迁移清单.md)。`/v3/api-docs`和`/actuator/prometheus`需operations:read；运营页面可下载OpenAPI。liveness反映进程状态，readiness检查MySQL/Redis/RabbitMQ/存储可用性。

奖励提现只模拟冻结、审批和结算，没有真实支付。AI仅做合成风险筛查；没有交付自动违章定责、边缘设备部署、云厂商存储、ES或分库分表，也没有虚构QPS、在线用户数或模型准确率。
