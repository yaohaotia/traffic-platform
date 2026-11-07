# P5 审核实施契约（已通过阶段验收）

本文描述P5当时的意图适配器边界；最终local/prod已在P6接入同事务预算预留与奖励订单，当前完整集成见P8-contract.md和项目README。

P4 gate已通过，本文冻结P5生产/测试接口。P5不发奖励、不预留预算。

## 消息与配置

- 复用 `${namespace}.completed`，监听器ID `trafficReviewCreationListener`，MANUAL ACK，`traffic.review.listener-enabled`控制自动启动；旧completed queue不增加/改变x-args。
- 毒消息手动投递既有 `${namespace}.dead.exchange` / `dead`，等待Confirm且无Return后才reject原消息；投递失败requeue。原completed队列无DLX，不直接reject后假称进入死信。
- 有效消息数据库失败NACK requeue(true)，带短退避，可在依赖恢复后重新处理。
- `traffic.review.polling-enabled`默认false，local显式true；poller调用`ReviewTasks.recoverExpired()`，每轮<=100。P7再替换为XXL-JOB调度。`lease-seconds=1800`、`max-transfers=3`、`dependency-backoff-ms=100`可配置。
- `ReviewCompletionConsumer.process(ReviewEvent)`返回ACK/REJECT。`ReviewEvent`字段同P4完成事件：schemaVersion,eventId,eventType,taskId,reportId,evidenceId,userId，eventType必须DETECTION_COMPLETED。
- 新增durable `${namespace}.review.decided`停放队列，绑定events exchange的review.decided；OutboxPublisher支持REVIEW_APPROVED/REJECTED/ESCALATED，P6再增加消费者。

## V5表

- `review_task`：id(UUID),report_id UNIQUE,source_event_id,source_detection_version,region_code,stage(PRIMARY/RECHECK),status(READY/CLAIMED/APPROVED/REJECTED/MANUAL_REQUIRED),version(初始0),assignee_id,preferred_reviewer_id,lease_token,lease_until,primary_reviewer_id,transfer_count(0),created_at,updated_at,decided_at。
- `review_decision`：id(UUID),task_id,task_version（请求领取版本）,stage,actor_id,decision,reason,lease_token_hash,created_at；UNIQUE(task_id,task_version)。只存token摘要，重复决议匹配actor/decision/reason/token摘要。
- `review_audit`：id自增,task_id,operation,actor_id(nullable系统操作),from_version,to_version,reason,created_at。
- `message_inbox`：consumer_name,event_id复合PK,aggregate_id,outcome,processed_at；consumer_name固定review-task-creation-v1。
- `review_approval_intent`：id(UUID),decision_id UNIQUE,report_id UNIQUE,task_id,task_version,user_id,campaign_id,amount,status(AWAITING_REWARD_INTEGRATION),created_at。
- `review_appeal`：id(UUID),report_id UNIQUE,task_id,appellant_id,reason,status(OPEN/RESOLVED),created_at,resolved_at。每报告最多一次申诉，旧决议永远保留。

测试清理顺序：review_appeal、approval_intent、review_decision、review_audit、review_task、message_inbox，再P4/P3表（detection_operation、attempt、outbox、task、report、evidence、campaign），再identity。

创建工单使用报告锁→工单锁顺序；Inbox与工单/CREATED审计同事务。事件校验数据库Outbox来源及task/report/evidence/user身份，读取成功检测版本，WAITING_REVIEW→PRIMARY/READY，RISK_REVIEW→RECHECK/READY。已处理事件及同report已有工单幂等ACK；非法来源REJECT；数据库故障异常抛出，不提前ACK。

## API与权限

前缀 `/api/v1/review-tasks`。查询/领取/决议成功200，参数400、权限403、不存在404、版本/租约/终态409。身份只用JWT。

- GET `?status=READY&stage=PRIMARY&page=1&size=20`，返回`items,page,size`，size1..100。不传status表示全部可见状态，待办页面显式传READY。要求report:review或任一review权限；服务端区域过滤，管理员review:manage+identity:user:manage可跨区域看运维详情，但不能跨区实际审核。
  普通审核员显式READY才应用候选过滤（claim/阶段权限、禁止本人提交、排除既往决议人和仅定向他人）。未传status或查询终态时是区域只读概览，不因曾经审核或当前没有该阶段终审权限而隐藏可读历史，与GET详情范围一致。拥有review:manage的区域运维人员可只读查看区域全部READY/CLAIMED/历史，不要求同时拥有claim/decide/recheck，也不因此获准领取或决议。普通审核员显式CLAIMED仅返回本人领取；区域管理可看区域全部。概览可包含本人提交的任务元数据，但领取/决议/审核看图仍严格禁止自审，owner图片使用自身预览接口。
- GET `/{id}`：TaskView，无leaseToken。TaskView字段id,reportId,regionCode,stage,status,version,assigneeId,preferredReviewerId,leaseUntil,primaryReviewerId,transferCount,sourceDetectionVersion,score,threshold,modelVersion。
- GET `/{id}/evidence`：按工单ID读取图片。必须绑定该区域（global管理员也不能跨区读图片）；普通审核人还须是CLAIMED状态下租约有效的当前领取人并有阶段权限，或有同区域review:manage运维权限。普通未领取/他人领取/租约过期均403。提交人走owner预览接口，不借审核员路由自审。
- GET `/api/v1/evidence/{evidenceId}/content`：已认证owner且有report:read/submit/review之一才能预览，非owner403。两图片接口均按数据库对象标识读取，无URL/path/objectKey入参；存储I/O必须无活动数据库事务。返回原始二进制、image/png或image/jpeg、Cache-Control:no-store、X-Content-Type-Options:nosniff、安全inline服务端文件名。读取结果大小/SHA256与上传元数据不符返回通用503，不输出路径。
- POST `/{id}/claim`：`{expectedVersion}`。要求review:claim及PRIMARY的review:decide或RECHECK的review:recheck，绑定区域、禁止报告提交人自审，RECHECK禁止primary_reviewer_id本人。返回ClaimView扁平字段id,reportId,stage,status,version,assigneeId,leaseToken,leaseUntil。
- POST `/{id}/decisions`：`{version,leaseToken,decision,reason}`，decision=APPROVE/REJECT/REQUEST_RECHECK，reason1..500。当前领取人、版本、token、数据库租约都必须有效。PRIMARY可升级RECHECK，记录primary_reviewer_id、READY、清领取信息且version+1；RECHECK不可再次升级。APPROVE/REJECT终审version+1并更新report.review_status，写不可变决议/审计/Outbox。重复完全相同决议返回当前TaskView，内容冲突409，不能借终态幂等冒充其他人。
- POST `/{id}/reassign`：`{expectedVersion,targetReviewerId,reason}`。review:manage且区域（identity:user:manage可跨区域管理）；禁止终态。目标必须启用、属于区域并有阶段权限，不能提交人/首审人。工单READY、preferred_reviewer_id=目标、assignee_id/token/lease清空、version+1。之后只有目标能领取，管理员响应不泄露目标token。
- GET `/{id}/audit?page=1&size=20`：PageResult，AuditView字段id,operation,actorId,fromVersion,toVersion,reason,createdAt，不返回token或摘要。
- POST `/api/v1/reports/{reportId}/appeals`：`{reason}`，仅提交人且有report:read或report:submit，报告和工单均REJECTED且reward_status=NONE、没有历史申诉。保存一次review_appeal(OPEN)，工单RECHECK/READY、version+1、primary_reviewer_id设为上一终审人，清领取信息，report.review_status=RISK_REVIEW，写APPEALED审计。返回AppealView{id,reportId,status=OPEN,reviewStatus=RISK_REVIEW}。已有申诉/非驳回/奖励已开始409，非本人403。RECHECK领取/决议还禁止该工单所有既往决议人，避免申诉时原终审者或首审者再次自我复核。复核终审时申诉status=RESOLVED，保留原reason/旧decision。

PRIMARY默认要review:decide；RECHECK要review:recheck；review:claim只允许领取，不授予终审能力。新bootstrap管理员赋review:claim/decide/recheck/manage，已有账号不自动改权限。

## 超时与P6端口

`ReviewTasks.recoverExpired()`短事务按报告→工单锁序，CAS仅回收CLAIMED且DB租约到期者：version+1、transfer_count+1、清领取和preferred；未达上限READY，达到上限MANUAL_REQUIRED，不无限重排；写审计并返回回收数量。reassign可显式接管MANUAL_REQUIRED，不偷偷重置历史transfer_count。

`ReviewCompletionConsumer.recoverMissing()`有界扫描至多100个SUCCEEDED检测、report WAITING_REVIEW/RISK_REVIEW、没有review_task且有SENT DETECTION_COMPLETED来源的报告，从数据库canonical字段构建ReviewEvent，再逐个调用同一事务建单逻辑。poller同时调用该方法。它不绕过DEAD Outbox的显式重投限制，不在缺少完成来源时凭空建单。

`ApprovalPreparationPort.prepare(ApprovedDecision)`必须在已有事务内执行；P5的默认实现只写review_approval_intent，reward_status仍NONE。P6用同事务预算预留/唯一reward_order/Outbox替换，不能另开事务或调用外部服务。APPROVE中port失败必须使决议/审计/工单终态/报告/Outbox共同回滚。P5历史批准意图P6需显式有审计的激活，不静默补发。

ApprovedDecision.taskVersion是请求的领取版本，终审完成后的review_task.version及事件reviewVersion为taskVersion+1。预算准备后落终态或升级复核的最终UPDATE仍须CAS校验CLAIMED、assignee、version、token和DB lease有效，不能只依赖事务开始时的校验；租约在等待预算锁时到期将整笔409回滚。

实际poller可用性与真实RabbitMQ重复事件、DLQ、数据库失败恢复、并发领取、租约回收、复核隔离、审批端口失败回滚均为P5门槛；测试默认关闭自动listener/poller，P5suite独立namespace并显式启停。
