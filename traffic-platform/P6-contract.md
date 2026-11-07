# P6 激励账务实施契约（已通过阶段验收）

`traffic.reward.enabled` 控制奖励审批适配器、HTTP入口及MQ入口；local/prod启用，早期阶段测试默认关闭，P6 suite显式启用。默认关闭只用于基础/早期关卡隔离，实际部署配置已启用。

## 资金状态与事务

- 审核APPROVE在原事务内先写review_decision，再经RewardApprovalPreparation保存意图、锁campaign、检查可用预算、写RESERVED奖励单/RESERVE预算流水/REWARD_RESERVED Outbox；最后仍要通过审核租约CAS。预算不足409或任一后续失败全部回滚。报告奖励金额使用P3快照。
- campaign的budget_amount为总额，budget_reserved为占用，budget_spent为净支出；可用=总额-占用-支出。数据库CHECK保证非负和不超总额。`reward_order.report_id/decision_id`分别唯一。
- 消费REWARD_RESERVED：报告→订单→campaign→wallet锁序，RESERVED→CREDITED、预算占用转支出、钱包CREDIT流水与REWARD_CREDITED Outbox同事务；Inbox与资金变动同事务，提交后才ACK。重复及取消后的迟到信号不恢复旧订单。
- 订单更正仅reward:manage且绑定区域，禁止对自己奖励执行管理更正。RESERVED释放占用并CANCELLED；CREDITED余额足够写REVERSAL/REFUND并REVERSED，旧CREDIT保留。余额被提现冻结/模拟支出占用时保存MANUAL_REQUIRED，不扣负余额、不退预算；阻止新提现和已有提现的APPROVE。可以先驳回提现释放冻结，再使用同原因重试更正。
- P5历史AWAITING_REWARD_INTEGRATION意图必须显式activate并记录actor/reason，不随升级自动支付。
- 所有金额DECIMAL(12,2)/BigDecimal，HTTP服务层校验正数、精度及上限；所有请求身份来自Security。

## API

前缀`/api/v1`；成功200，校验400、权限403、不可见记录404、资金/状态/幂等冲突409。

| API | 权限/用途 |
| --- | --- |
| GET /wallet、/wallet/entries、/rewards | wallet:read，仅本人；分页page/size |
| GET /campaigns/{id}/budget | reward:manage且绑定区域 |
| POST /campaigns/{id}/budget-additions | 同上；Idempotency-Key；{amount,reason}，同号不同内容409 |
| POST /approval-intents/{id}/activate | 同上；{reason}；唯一历史意图激活审计 |
| POST /rewards/{id}/corrections | 同上；{reason}；每订单唯一补偿单，重复不重复冲账 |
| POST /withdrawals | wallet:withdraw，{amount,regionCode}与Idempotency-Key；区域须属于本人，可用→冻结 |
| GET /withdrawals | wallet:read，仅本人 |
| GET /management/withdrawals?state=REQUESTED | withdrawal:manage，强制区域过滤 |
| POST /withdrawals/{id}/decisions | withdrawal:manage且区域，{decision:APPROVE/REJECT,reason}；禁止自审批；模拟支付/驳回释放 |
| GET /wallet/reconciliation | wallet:read，本人一致性快照 |
| GET /campaigns/{id}/reconciliation | reward:manage且区域，预算/订单/不可变流水比较 |
| GET /notifications；POST /notifications/{id}/read | notification:read，只读/修改本人通知，越权404 |

提现状态REQUESTED/SIMULATED_PAID/REJECTED；无真实支付、银行卡或支付SDK。原系统自动余额不导入为新账务初始资金。

## MQ 与恢复

- 新持久队列`${namespace}.reward`绑定reward.reserved。通知使用独立`${namespace}.notifications`，绑定review.decided、reward.reserved、reward.changed，避免竞争消费造成漏通知；原review.decided停放队列属性不变。
- Outbox新增REWARD_RESERVED路由reward.reserved，REWARD_CREDITED/CORRECTED路由reward.changed。消息正文只存schemaVersion/eventId/eventType/reportId/orderId，消费者核对数据库规范来源，再从数据库取金额与收件人。
- 未确认的毒消息转存dead.exchange/dead且Confirm成功无Return后才移除原消息；数据库故障NACK重投且短退避。
- recoverCredits和recoverNotifications只补偿已SENT事件，有界100条；不绕过DEAD投递限制、不自动激活历史意图。local调度驱动，P7接XXL-JOB。
- LedgerReconciliation提供一致性快照和每批100个campaign/100个wallet的游标扫描；比较预算与订单/流水，钱包与流水/提现/奖励事实，发现差异只报告不覆盖数据。

P6验收证据以`../docs/test-reports/P6-summary.json`为准。
