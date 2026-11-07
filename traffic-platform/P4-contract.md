# P4 实施契约（已通过阶段验收）

配置：`traffic.messaging.enabled`，local=true、test/integration=false；P4 suite显式true。`traffic.messaging.namespace=traffic`，`traffic.messaging.polling-enabled=true`、`listener-enabled=true` 可分别关闭。消息拓扑：`${namespace}.events` direct exchange，`${namespace}.detection` queue绑定`detection.requested`，`${namespace}.completed`绑定`detection.completed`，`${namespace}.dead`绑定`${namespace}.dead.exchange`的`dead`。queue durable、message persistent，监听MANUAL ACK；消费者bean id `trafficDetectionListener`。Rabbit配置从spring.rabbitmq（local端口25672）读取。

`traffic.messaging.confirm-timeout-ms=5000`，`outbox-lease-seconds=30`，`max-publish-attempts=10`；`traffic.detection.model-url=http://127.0.0.1:5001/api/v1/detect`、`http-timeout-ms=10000`、`lease-seconds=30`（必须>HTTP超时）、`max-attempts=3`、`retry-base-ms=1000`、`pending-resignal-seconds=60`。

V4追加：outbox_event(lease_token,lease_until,next_attempt_at,last_error_code,last_error_message,published_at,updated_at)，detection_task(lease_token,lease_until,next_attempt_at,last_signal_at,model_version,result_score,result_threshold,last_error_code,last_error_message,completed_at,updated_at)。新增`detection_attempt(task_id,version,lease_token,started_at,finished_at,outcome,error_code)` unique(task_id,version)，`detection_operation(id,task_id,operator_id,operation,reason,created_at)`。删除测试数据顺序先operation/attempt，再旧P3顺序。

实现依据实际下载的Spring AMQP4.1.2 jar：CorrelationData读取returned的方法是`getReturned()`，通过javap核对；不能照搬网页示例的`getReturn()`。detection_operation另有可空event_id供消息人工重投审计。

包 `com.yao.traffic.detection` 与 `com.yao.traffic.messaging`，公共类型 `DetectionModels.Event(int schemaVersion,String eventId,String eventType,String taskId,long reportId,long evidenceId,long userId)`、`Claim(String taskId,long reportId,long evidenceId,String objectKey,long version,String leaseToken)`、`Result(double score,double threshold,String modelVersion,boolean isAiGenerated)`。

测试可调用的实际生产编排方法（无测试探针）：

- `OutboxPublisher.publishPending()` 返回本轮成功数，执行真实broker confirms/returns；`OutboxStore.claim(int limit)`返回`OutboxStore.Delivery(eventId,eventType,payload,leaseToken)`；`markPublished(eventId,leaseToken)`、`markFailed(eventId,leaseToken,code)`均boolean CAS；`recoverExpired()`返回回收数。
- `DetectionTasks.claim(Event)` 返回Optional<Claim>；`succeed(Claim,Result)`、`fail(Claim,code,retryable)`返回boolean，均短事务且校验租约；`recoverAndEnqueue()`返回回收/重试/补信号合计数。
- `DetectionProcessor.process(Event)`返回`ACK`或`REJECT`，claim→事务外multipart→短事务succeed/fail；`onMessage`由独立监听器适配，非法协议reject到真实DLQ。只有成功/持久化失败状态后ACK；DB异常向容器抛出，不伪造成功。
- `ModelClient.detect(Claim)`读取StorageAdapter对象并发送multipart image至固定model-url；max响应64KiB，严格schemaVersion/score/threshold/modelVersion/isAiGenerated，硬超时；不接受响应类型强转或非法数值。

Outbox状态PENDING/PUBLISHING/SENT/DEAD；task状态PENDING/RUNNING/RETRY_WAIT/SUCCEEDED/DEAD。task version每次领取递增；claim唯一执行token，所有完成/失败以version/token/leaseCAS。成功report.detection_status=SUCCEEDED，review_status低风险WAITING_REVIEW/高风险RISK_REVIEW；reward_status不变。失败DEAD不会直接处罚用户。

PENDING漏信号自动恢复仅在存在SENT输入事件、且不存在PENDING/PUBLISHING/DEAD输入事件时执行；DEAD投递必须由运维显式重投，不自动创建新事件绕过投递次数上限。人工任务重跑不会偷偷修改旧DEAD消息，运维仍需处理这些历史DEAD输入事件。

自动/人工重试产生新Outbox事件（DETECTION_REQUESTED）；原投递重发维持eventId。完成原子产生DETECTION_COMPLETED事件，仅进completed队列，不再触发检测。RETRY_WAIT到期、RUNNING租约过期、PENDING漏信号由recoverAndEnqueue原子恢复。失效旧结果只能被忽略，不能改report。

API：GET `/api/v1/detection-tasks/{id}` owner仅可看脱敏进度，detection:manage仍需所在区域（identity:user:manage可跨区）；POST `/{id}/retry` JSON`reason`（1..500）要求detection:manage且区域，只允许DEAD/RETRY_WAIT且report未终审。GET `/api/v1/outbox-events?status=DEAD`、POST `/api/v1/outbox-events/{id}/retry` JSONreason要求message:manage，关联区域同样检查。成功GET200、retry200，冲突409、无权403。bootstrap仅新建管理员附加detection:manage/message:manage。

新Pythonmultipart契约：POST `/api/v1/detect` image；200 `{schemaVersion:1,score,threshold,modelVersion,isAiGenerated}`，bool严格等于score>threshold。Java限制modelVersion为1..128 ASCII `[A-Za-z0-9._:-]`。原/detect保留旧接口。P4 HTTP桩只证明契约，P8另测真实模型。
