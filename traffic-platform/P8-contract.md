# P8 当前集成契约与验收

当前完整入口为Vue工程src/traffic及独立Docker/Nginx部署。P0—P8均已通过，最终证据为../docs/test-reports/P8-final-summary.json。P3—P7文档记录各阶段接口，本文补充最终管理与迁移接口。

## 账户管理

以下均要求identity:user:manage，不提供匿名注册或通过旧角色ID提权。

- GET /api/v1/identity/users?page=1&size=20：受限分页，返回id/username/enabled/auth_version/roleIds/regionCodes，永不返回密码摘要。
- GET /api/v1/identity/catalog：已定义角色、区域和权限代码。
- POST /api/v1/identity/users：{username,initialPassword,roleIds,regionCodes}；用户名3—100位ASCII账号字符，密码至少12位且UTF8最多72字节，BCrypt12；账号已存在409。
- PUT /api/v1/identity/users/{id}/access：{roleIds,regionCodes}，校验角色/区域存在，替换后auth_version+1，旧凭据失效；不允许调整本人授权。
- PUT /api/v1/identity/users/{id}/password：{password}，增加auth_version并记录审计。
- PUT /api/v1/identity/users/{id}/enabled：{enabled}，停用撤销旧凭据，不允许管理员自行停用本人。
- POST /api/v1/identity/regions：{code,name}；POST /api/v1/identity/roles：{code,permissionCodes}。代码使用大写字母/数字/下划线/横线，授权仅引用已定义权限。

身份创建、授权替换、密码重置和角色/区域创建记录identity_audit，不记录密码值。

## 运营账务查询

GET /api/v1/management/rewards及/management/approval-intents均要求reward:manage，并强制campaign区域过滤。前者分页列出区域奖励单，后者仅列AWAITING_REWARD_INTEGRATION历史意图，支持管理页面直接核对并操作，避免盲填账务UUID。更正/激活继续使用P6的理由、幂等及事务约束。

## 历史档案

- POST /api/v1/migration/preview，要求identity:user:manage。输入{schemaVersion:1,users:[{legacyId,username}],reports:[{legacyId,userLegacyId,status,location,description}]}，单批最多1000账号/1000线索，返回sourceSha256、数量、映射状态、issues和canImport。sourceSha256基于服务端接受字段的规范序列化，不是原始JSON文件字节摘要。
- POST /api/v1/migration/import：{bundle,userMappings,expectedSha256,reason}。每个原账号显式映射到已创建的新账号，目标可停用且不会被导入过程启用；预演摘要不符409，同文件不同映射409，重复相同导入返回原批次，已有原线索禁止覆盖。
- GET /api/v1/legacy-reports：report:read或report:submit，仅当前本人关联的历史档案。

原0/1/2/3/4分别映射LEGACY_PENDING_DETECTION、LEGACY_RECHECK_REQUIRED、LEGACY_PENDING_REVIEW、LEGACY_APPROVED_NO_SETTLEMENT、LEGACY_REJECTED。原状态不被当成当前模型结论、处罚或可支付奖励；旧密码、角色权限、银行卡、余额及远程图片抓取不进入本导入流程。

tools/export-legacy.py显式只读事务并rollback；--all-users用于受控的小批全账号导出。源账号启停元数据由显式目标账户准备工具保留，归档导入本身不创建/提权/启用账户。实际11账号/44线索预演与页面导入已通过。

## 完整证据

138项Java、25项Python协议、9项真实Edge页面检查全部通过；真实RPTC权重、模型重启恢复、不同审核员复核、账务与通知链路已经验证。最终运行Jar/前端/权重摘要核对一致，应用重启readiness正常，五类维护任务近期成功，资金对账差异0。模型验证不等同于有标注准确率评测，提现仅为模拟结算。
