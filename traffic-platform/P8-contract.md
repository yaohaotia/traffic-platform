# P8 当前集成契约与验收

当前完整入口为Vue工程src/traffic及独立Docker/Nginx部署。P0—P8均已通过，最终证据为../docs/test-reports/P8-final-summary.json。P3—P7文档记录各阶段接口，本文补充当前账户与运营接口。

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

## 独立版本

平台已移除历史档案展示、预演和导入功能，对应接口不再注册；账户管理只处理当前平台的账号、角色和区域。Flyway已执行脚本保持不可变，旧表不再作为运行功能使用。当前回归结果见../docs/test-reports/standalone-summary.json。

## 历史阶段证据

138项Java、25项Python协议、9项真实Edge页面检查全部通过；真实RPTC权重、模型重启恢复、不同审核员复核、账务与通知链路已经验证。最终运行Jar/前端/权重摘要核对一致，应用重启readiness正常，五类维护任务近期成功，资金对账差异0。模型验证不等同于有标注准确率评测，提现仅为模拟结算。
