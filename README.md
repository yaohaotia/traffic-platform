# 交通线索智能审核与激励平台

面向校园、园区的交通线索管理，完成专项征集、证据上传、图片合成风险筛查、人工审核与独立复核、预算奖励和站内通知。通过区域授权控制业务范围，使用可靠消息与账务流水追踪处理过程。

模型用于辅助筛查图片合成风险；违章事实由人工核验。本项目没有对接交管处罚或真实支付，提现为模拟结算。

## 技术栈

- 后端：Java 21、Spring Boot 4、Spring Security、JWT/BCrypt、MyBatis-Plus、Spring JDBC、MySQL、Flyway。
- 会话与异步：Redis/Lua、RabbitMQ、Outbox/Inbox、发布确认、手动 ACK、Redisson、XXL-JOB。
- 前端与模型：Vue 2、Element UI、Axios；Python、Flask、PyTorch、PatchCraft/RPTC。
- 部署与运维：Docker Compose、Nginx、Actuator、Micrometer/Prometheus、OpenAPI。

完整版本和职责见[架构与技术栈](docs/架构与技术栈.md)。

## 目录

| 目录 | 内容 |
| --- | --- |
| `traffic-platform` | 新版 Java 后端、Flyway 迁移、集成测试、Compose 与运维脚本 |
| `SpringBoot-Vue（前端）` | 前端源码，新入口位于 `src/traffic`，使用 `build:traffic` 构建 |
| `PatchCraft（算法）` | 模型服务、预处理与网络代码、已有 RPTC 权重及协议测试 |
| `docs` | 分阶段计划、设计、接口迁移、演示手册与验收摘要 |

此仓库包含新版运行所需源码。原后端属于本机历史工程，未合并到本仓库；历史数据迁移工具需要另行提供原配置与只读数据源。原前端页面保留为迁移参考，新版使用独立入口。

## Windows 首次启动

环境：Docker Desktop（Linux 容器）、PowerShell 7、JDK 21、Maven、Node.js/npm，以及固定解释器 `D:/soft/Anaconda/python.exe`。脚本默认 Java/Maven 路径见后端 README；`tools/build.ps1` 支持通过 `-JavaHomePath`、`-MavenHomePath` 显式指定。

以下从仓库根目录执行。依赖安装到仓库旁的 `.cache/python-model`，不修改 Anaconda 全局依赖。

```powershell
& 'D:/soft/Anaconda/python.exe' -m pip install --target '../.cache/python-model' -r './PatchCraft（算法）/service/requirements-cpu-windows.txt'

Push-Location './SpringBoot-Vue（前端）'
npm.cmd ci
Pop-Location

Set-Location './traffic-platform'
docker compose -f './dev/compose.yml' up -d --wait
& './tools/build.ps1' -Goals @('verify')
& './tools/build-web.ps1'

# 只在首次初始化时执行，会生成随机密码；已有 .env 时拒绝覆盖
& './deploy/initialize.ps1'
New-Item -ItemType Directory -Path './deploy/web' -Force | Out-Null
Get-ChildItem -LiteralPath '../SpringBoot-Vue（前端）/dist-traffic' | Copy-Item -Destination './deploy/web' -Recurse -Force
docker compose -f './deploy/compose.yml' --env-file './deploy/.env' up -d --build --wait --wait-timeout 180
```

另开 PowerShell，在 `traffic-platform` 目录启动模型，并保持该窗口运行：

```powershell
& './tools/start-model.ps1'
```

随后在另一个 PowerShell 的 `traffic-platform` 目录初始化演示账号：

```powershell
& './tools/seed-demo.ps1'
```

平台入口：<http://127.0.0.1:28090>；XXL-JOB 管理端：<http://127.0.0.1:29090>。

业务账号和随机密码由当前部署生成，位于 `traffic-platform/deploy/.generated/demo-accounts.json`。普通用户、初审员、复核员、运营管理员分别使用 `traffic.user`、`traffic.reviewer`、`traffic.rechecker`、`traffic.manager`。没有发布统一默认密码。

调度管理端账号为 `traffic.admin`，密码位于部署 `.env` 的 `TRAFFIC_JOB_ADMIN_PASSWORD`。Compose 仅向本机回环地址发布入口；MySQL/Redis/RabbitMQ 通过容器网络访问。

## 本机已有部署时恢复

启动 Docker Desktop 后，在 `traffic-platform` 目录运行：

```powershell
docker compose -f './deploy/compose.yml' --env-file './deploy/.env' up -d --wait --wait-timeout 180
```

按上文单独启动模型。停止平台可使用相同 Compose 参数执行 `stop`；保留持久卷和 `.env`，下次继续使用原数据与凭据。

## 设计与验证

- [项目计划](docs/项目重构计划书.md)、[执行记录](docs/重构执行记录.md)、[架构决策](docs/架构决策记录.md)。
- [演示操作手册](docs/演示操作手册.md)、[接口迁移清单](docs/接口迁移清单.md)、[后端运行说明](traffic-platform/README.md)。
- 本机 P8 历史验收结果：138 项 Java、25 项 Python 协议测试、9 项真实浏览器检查通过，账务差异为 0；摘要见 [P8-final-summary.json](docs/test-reports/P8-final-summary.json)。这些是原验收环境的记录，新环境需重新运行验证。
- `tools/build.ps1 -Goals verify` 运行当前完整 Java 测试；分阶段门禁脚本和部署/浏览器验收见后端文档。真实模型需要项目权重及固定解释器依赖。

随机凭据、原业务数据库导出、上传证据、模型数据集、原始日志、浏览器截图、依赖目录及构建产物不纳入版本控制。保留结构化验收摘要供核对，原始运行记录仍在原本机工程。

第三方代码与权重的归属说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
