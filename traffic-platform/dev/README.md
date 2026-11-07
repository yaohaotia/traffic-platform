# 隔离开发/集成测试环境

这是专用本地测试环境，里面的密码是公开的开发样例，不能用于生产。端口全部绑定 127.0.0.1；不要将 Docker daemon 或这些端口暴露到公网。

```powershell
docker compose -f ./dev/compose.yml up -d --wait
docker compose -f ./dev/compose.yml ps
```

| 组件 | 本地端口 | 用途 |
| --- | --- | --- |
| MySQL 8.0.32 | 23306 | 新应用 traffic_platform、自动化测试 traffic_platform_it |
| Redis 6.2 | 26379 | 新应用会话与独立测试命名空间 |
| RabbitMQ 4.1 | 25672 / 25673管理 | 后续 P4 真实消息验收 |

MySQL/Redis 镜像先复用本机缓存以完成隔离测试，正式部署前在 P7 重新审核版本和补丁。RabbitMQ 镜像预下载不代表业务已经接入 MQ。

容器/卷使用 traffic-platform-dev 项目前缀，不使用原 13306/16379 服务。自动化测试只能清理 traffic_platform_it 表和指定测试 Redis 命名空间，不能 FLUSHALL 或操作原库。

RabbitMQ 固定节点主机名，避免重建容器时节点名随容器 ID 改变。健康检查要求 Rabbit 应用和 AMQP 5672 监听器都已就绪，不能仅凭 Erlang 进程 ping 成功就开始连接；故障恢复测试另有真实 AMQP 连接验证。

`docker compose -f ./dev/compose.yml stop` 可停止本环境；不要用带 `-v` 的删除命令，避免误删开发数据。测试启动失败时关卡保持未通过。
