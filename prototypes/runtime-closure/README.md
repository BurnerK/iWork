# Issue #7 · iWork 运行闭环验证

这是供审阅的可丢弃原型。使用 AgentScope Java **2.0.4** 的 ReAct 工具调用、执行事件和订阅取消。模型是确定性替身，仅输入合成资料。本机运行无需 API Key、部署环境或外部模型。两枚固定身份令牌仅供回环演示。

## 共同审阅入口

双击打开 [logic-demo.html](logic-demo.html)，可使用自由操作按钮和五组逐步引导审阅状态模型。页面在内存中模拟断连、串行、隔离、取消、故障和用量，刷新即重置。该演示用于共同判断业务状态是否符合预期；下方 HTTP/JVM 探针提供独立的 SDK 集成证据。

## 复跑

需要 JDK 21、Maven 3.9+ 和 Python 3。首次编译从 Maven Central 解析公开依赖。验证脚本启动两个先后运行的 JVM，使用系统临时目录下的 `codex/`，退出时清除本次测试数据。

```sh
cd prototypes/runtime-closure
mvn -s settings.xml -q compile org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath -Dmdep.outputFile=target/classpath
python3 verify.py
```

手动审阅时，复用已编译的 classpath，启动后打开 `http://127.0.0.1:<打印的端口>/`。每个临时目录只启动一个进程。

```sh
mkdir -p "${TMPDIR:-/tmp}/codex"
probe_dir=$(mktemp -d "${TMPDIR:-/tmp}/codex/iwork-review.XXXXXX")
java -cp "target/classes:$(cat target/classpath)" RuntimeProbe "$probe_dir"
```

1. 提交资料，记下任务 ID，关闭页面，再打开并点击「查任务」。`phase: staged` 说明 SDK 已完成工具调用。点击「允许保存」触发发布，再查询可见 `COMPLETED` 与正文。重复发送原请求键和相同参数会查回原任务。
2. 暂不发布，换请求键，用同一用户和会话提交，应返回 409。切换为 Bob，用 Alice 的任务 ID 查询应返回 404。Bob 可独立发起同名会话的任务。
3. 创建修改任务，填入已完成任务的 ID 作为旧版本。待草稿就绪后先取消、再允许保存，查询 `latePublishRejected: true`。用「保存失败」场景重复，发布后查询 `FAILED`，旧版本仍可读。
4. 「模型调用挂起」时第二笔调用用量为 `null`。取消后查询可看到模型替身订阅收到取消。模型失败也保留已收到的用量。验证脚本还会强制终止 JVM，再从相同目录启动并检查中断和已保存版本。

「允许保存」是故障注入开关，用来确定性复现发布前的时点。它不代表首版产品需要人工批准。原型也用任务 ID 标识每次不可变的文档版本，未定义正式的成果标识或版本接口。

## 实测记录 · 2026-11-12

本机 macOS arm64、OpenJDK 21.0.13.0.3、Maven 3.10.0、Python 3.9.6。SDK 固定为 `io.agentscope:agentscope-core:2.0.4`。首次构建有两个公开 JSON Schema 依赖遇到 Maven Central TLS 握手失败，本次从本机公开构件缓存补齐相同版本后编译。下面是 HTTP/JVM 验证脚本实际断言，浏览器入口经 HTTP 检查。

| 组合问题 | 实测现象 | SDK 与应用的职责 |
| --- | --- | --- |
| 断连、重连、重提 | 提交请求发出后直接关闭 TCP 连接。服务继续执行两个真实 SDK 工具，再由独立连接查回。四次并发同请求返回同一个 ID。同键改参数和同会话另一请求返回 409。 | SDK 提供执行流。服务端后台 worker 持有订阅，业务记录承担观察与幂等。 |
| 会话串行、用户隔离 | Alice、Bob 的同名会话可同时进入草稿阶段，工作文件和正式文档只包含各自资料。Bob 查询、取消或引用 Alice 的版本返回 404。 | SDK 调用只注册本任务的内置工具。应用把授权后的资料放进按任务划分的工作区，并验证 HTTP 调用者与归属。 |
| 取消、故障、版本保护 | 草稿就绪后取消，再尝试发布被拒。已发布后取消仍为完成。保存前注入 I/O 异常时记录失败且无新版本。强杀 JVM 时，草稿阶段和模型调用阶段的任务均在重启后显示中断。原版本和正常修改版完整，重提不会重启中断任务。 | SDK 订阅可取消。应用让取消与发布在同一状态写入边界内竞争，用一次文件替换提交状态及正式正文，启动时归档遗留运行任务。 |
| 用量 | 成功任务收到三笔模拟用量 11、12、13。失败和模型调用中取消时，第一笔 11 保留，第二笔调用存在但为 `null`。强杀并重启后也保留未知。模型替身的 `doOnCancel` 收到取消。 | SDK 提供 `ModelCallStartEvent`、`ModelCallEndEvent` 与 `ChatUsage`。应用按任务和调用 ID 归属，先保存未知，再用已收到的 SDK 用量更新。 |

## 对 Issue #6 的约束

- Web 连接可以独立于执行，但运行状态、幂等与成果发布需要一个可恢复的业务权威。这个单 JVM 原型用同步入口和原子文件替换验证了语义。多实例方案需另证跨实例的会话互斥、执行归属、取消和提交条件，以及重启时如何避免把其他实例的活动任务误标为中断。
- 独立目录配合固定工具足以验证本探针的访问边界。真实身份、任意文件解析、OS 级隔离和崩溃后的工作区清理未验证。原子替换覆盖本次 JVM 强杀场景，断电和磁盘完全不可写时的持久性不在证据内。
- 下一步需选定试用模型与连接条件，验证真实工具回合、流式用量何时出现、取消后上游是否继续执行或计费，以及如何对账。SDK 收到的模拟 token 和取消信号不能用于确认供应商结算或外部工具回滚。
- 本次报告可供共同审阅。[Issue #7](https://github.com/BurnerK/iWork/issues/7) 保持开放，[Issue #6](https://github.com/BurnerK/iWork/issues/6) 继续受其阻塞。
