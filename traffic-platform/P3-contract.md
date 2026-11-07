# P3 API 与数据库契约

生产实现与测试使用本契约；尚未进行 P3 验收。HTTP 成功均200（发布/关闭返回更新后的任务）；错误沿用 ApiError，验证400、无权403、不存在404、冲突409、限流429。

## API

- `POST /api/v1/campaigns`：`title,regionCode,startsAt,endsAt,rewardAmount,budgetAmount`，时间为ISO Instant。权限`campaign:manage`。返回`id,title,regionCode,startsAt,endsAt,rewardAmount,budgetAmount,state`，初始DRAFT。奖励>0，预算>=单次奖励，金额最多2位小数，开始<结束。
- `POST /api/v1/campaigns/{id}/publish`：DRAFT→PUBLISHED，已结束不可发布。重复发布幂等；CLOSED不可重新发布。
- `POST /api/v1/campaigns/{id}/close`：DRAFT/PUBLISHED→CLOSED，重复关闭幂等。
- `GET /api/v1/campaigns?page=1&size=20`：返回`items,page,size`；size1..100，普通用户只看绑定区域PUBLISHED任务，campaign:manage可看全部。
- `POST /api/v1/evidence`：multipart字段`image`，权限`report:submit`，真实PNG/JPEG且<=5MiB，最大2000万像素。返回`id,sha256,mediaType,sizeBytes`。原文件名不用作路径。
- `GET /api/v1/evidence/{id}`：仅owner，返回相同元数据；不公开文件系统路径。
- `POST /api/v1/reports`：JSON`campaignId,evidenceId,location,description,captureTime`，权限`report:submit`。头`Idempotency-Key`为1..128位`[A-Za-z0-9._:-]`。返回`id,campaignId,userId,evidenceId,regionCode,location,description,captureTime,rewardAmount,detectionStatus,reviewStatus,rewardStatus`。新状态PENDING/WAITING_DETECTION/NONE。未来拍摄时间拒绝（允许60秒时钟偏差）。
- `GET /api/v1/reports/{id}`、`GET /api/v1/reports?page=1&size=20`：权限`report:read`或`report:review`，本人可读；有report:review且绑定相应区域可读其他人的；身份来自JWT。列表返回`items,page,size`，size1..100。存在但越权403。

提交仅允许当前用户已启用、征集任务PUBLISHED且now位于[startsAt,endsAt)、用户绑定区域、证据属于当前用户。同用户同Idempotency-Key相同内容返回原结果，不再检查当前任务或消耗限流；不同内容409。相同用户/任务/证据SHA256不同key拒绝409（重复上传同图也防重）。

## 表与字段

- `campaign`：id,title,region_code,starts_at,ends_at,reward_amount,budget_amount,state,created_by,created_at。
- `evidence`：id,owner_id,object_key,sha256,media_type,size_bytes,created_at。
- `traffic_report`：id,campaign_id,user_id,evidence_id,evidence_sha256,region_code,location,description,capture_time,reward_amount,detection_status,review_status,reward_status,request_key,request_hash,created_at。unique(user_id,request_key)与unique(campaign_id,user_id,evidence_sha256)。request_key保存原HTTP请求键UTF8字节的SHA256小写hex摘要，避免MySQL默认不区分大小写collation将A/a错误合并；HTTP请求键仍区分大小写。
- `detection_task`：id(UUID),report_id(unique),status(PENDING),attempt_count(0),version(0),created_at。P4再追加租约/结果字段。
- `outbox_event`：event_id(UUID PK),aggregate_type(REPORT),aggregate_id(reportId),event_type(REPORT_SUBMITTED),payload(TEXT JSON),status(PENDING),attempt_count(0),created_at。P4再追加投递租约等字段。JSON至少eventId,eventType,reportId,evidenceId,userId。

有外键的测试清理顺序：outbox_event、detection_task、traffic_report、evidence、campaign，再identity关系/用户/区域。P3测试不得在其他测试仍运行时清理表。

## 配置

- `traffic.storage.provider=local`仅local/test/integration profile允许。`traffic.storage.local-root`默认`./data/evidence`；测试应DynamicPropertySource设置独立临时目录。
- `traffic.report.rate-limit-per-minute=20`；Redis key `traffic:report:rate:{userId}:{UTC epochMinute}`，Lua INCR并EXPIRE 120；同key重放先查库不访问限流。测试可指定限制，用独立用户或只删自己rate key，不flush。
- `traffic.report.max-image-bytes=5242880`可下调，`traffic.report.max-image-pixels=20000000`。

本地Storage是显式开发适配器。prod未配置支持的对象存储提供方会拒绝启动，不把本地文件适配器当云对象存储实现。

## 一致性

单次提交用事务先FOR UPDATE锁当前identity_user，校验enabled后查请求键；避免MySQL RR下重复并发快照不可见。新提交校验规则后，同事务保存report（MyBatis-Plus mapper）、detection_task、outbox_event。后两项失败整单回滚。P3不消费事件、不发MQ、不调用模型、不发奖励。Redis计数不参与MySQL事务，失败的新请求可能消耗一次配额，但幂等重放不消耗。
