# P7 调度与运维契约（阶段回归及真实部署均已通过）

组件固定为XXL-JOB3.4.2、Redisson4.8.0社区版、springdoc3.2.0、Boot管理的Micrometer/Prometheus。版本API同时核对上游标签与实际下载Jar。

- `traffic.operations.enabled=true`启用维护协调、任务缓存、业务指标。local使用`scheduling.mode=local`，prod默认xxl；旧P4/P5/P6本地poller在operations启用时禁用，避免同实例重复驱动。
- XXL模式要求显式admin-addresses和至少32字符的非默认access-token。五个Handler为trafficOutboxJob、trafficDetectionRecoveryJob、trafficReviewRecoveryJob、trafficRewardRecoveryJob、trafficLedgerReconciliationJob。原生执行器端口不对公网发布。
- Redisson看门狗锁按namespace/job隔离，跳过被其他实例占用的批次；异常记录FAILED并向调度器返回失败。锁仅减少重复，数据库唯一键/租约CAS仍保证业务效果幂等。
- V7的maintenance_checkpoint记录成功/失败及对账游标；对账每批最多100任务/100钱包，有差异写ledger_drift、调度失败，并继续维护下一批游标，不自动修账。
- 征集详情GET /api/v1/campaigns/{id}使用30秒展示缓存，每次读取后仍按区域/发布状态校验。发布/关闭和预算追加只在提交后失效；失效失败有日志且TTL限制展示延迟，写流程从不使用缓存判断业务事实。
- GET /api/v1/operations/summary与/actuator/prometheus、/v3/api-docs需operations:read。该权限面向系统运维，可读取全局无个人详情的积压数量；资金与审核数据仍使用原区域/归属规则。
- /actuator/health/liveness只反映进程状态；local/prod的readiness包括db/redis/rabbit/evidenceStorage。依赖失败返回不可用，不切到内存会话。Prometheus查询失败使用NaN，不将未知积压伪报为0。
- shared-volume存储显式要求绝对挂载路径，所有副本共享同一持久卷；本地开发provider仍在prod禁止。对象键由服务端生成，拒绝路径穿越/符号链接，Nginx不公开原始存储目录。该实现不是云存储SDK。

部署目录deploy包含独立Compose、Nginx、容器非root运行配置、上游XXL固定SQL/许可证与首次初始化脚本。其资源名traffic-platform-deploy与原服务、测试服务均分离。初始化生成随机凭据，拒绝覆盖既有.env；去掉上游示例账号/任务，只种本项目维护任务。web占位页只有在P8前端通过后才替换。

专项OperationsIntegrationTest使用真实MySQL/Redis/RabbitMQ与原生XXL执行器；调度中心协议对端是明确的本地测试HTTP服务，不将其说成真实管理端UI。独立Compose部署另行验证真实XXL管理端及注册/维护日志，完成后才正式放行P7。
