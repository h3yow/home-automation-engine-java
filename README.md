# 智能家居自动化引擎（本地可运行）

一套不依赖真实硬件的智能家居自动化与设备状态协同引擎。用本地模拟设备替代硬件，
使**命令下发、状态上报、规则触发与失败恢复**在 HTTP 用户入口与内部自动化入口下
得到一致且可解释的结论。

- Java 21 + Spring Boot 3.4，内存存储，开箱即跑：`mvn spring-boot:run`
- 演示数据自动初始化：两个家庭 H1/H2、7 台模拟设备、多条规则（含默认禁用的成环规则对）

---

## 1. 状态模型（State Model）

### 1.1 设备能力模型 DeviceModel
每类设备按 `modelId + version` 登记能力模型（`ModelRegistry`）：

| 字段 | 含义 |
|---|---|
| `supportedCommands` | 设备支持的命令集合 |
| `commandParams` | 每个命令的必填参数 |
| `commandTarget` | 命令生效后改变的能力属性（用于冲突分组与生效确认） |
| `commandResult` | 无参命令（open/close/lock…）生效后写入的常量属性值 |
| `capabilities` | 允许上报/写入的能力属性集合 |
| `requiredReportFields` | 业务上报必填字段 |
| `commandValueDomain` | 参数允许的取值域（离散值） |

版本裁决（确定、不猜测）：精确版本命中；未登记版本回退到**不高于它的最新登记版本**
（旧模型对新固件前向兼容）；不存在任何 ≤ 该版本的登记模型则明确拒绝
`UNSUPPORTED_MODEL_VERSION`。

### 1.2 命令五态结论 `CommandOutcome`
- `ACCEPTED` 已受理：进入设备串行队列，尚无执行结论（重复/合并命令也以该态 + 原因表达）
- `EFFECTIVE` 已生效：驱动成功且状态已收敛到目标
- `REJECTED` 已拒绝：模型/参数非法、风暴策略、幂等重复等
- `TIMEOUT` 超时：设备长时间无响应
- `FAILED` 执行失败：设备离线或返回失败

每个结论带可机读的 `rejectReason`（`RejectReason`）与人类可读 `detail`，
失败上下文（阈值、原因、关联命令）落执行记录，可诊断。

### 1.3 状态收敛（StateConvergence）
- **单调序号**：携带 `seq` 且 ≤ 已收敛序号的上报判定为 `OUTDATED_SEQUENCE`，
  不采纳——**乱序/重复上报绝不让已生效状态无解释回退**；不带 seq 由收敛器在设备锁内分配。
- **未知能力**：上报出现模型未声明的属性 → `UNKNOWN_CAPABILITY` 拒绝，不静默忽略。
- **缺失字段**：带属性的业务上报缺必填字段 → `MISSING_REQUIRED_FIELD`。
- **离线语义**：`online=false` 只翻转在线位，**不覆盖任何能力属性、不记录为成功**。
- 仅在属性实际变化（或在线位翻转）时产生新快照并触发规则，避免重复触发。

---

## 2. 命令执行与并发一致性

- 每台设备一条**单工作线程 + 有界 FIFO 队列**（`PerDeviceCommandQueue`）：
  同一设备命令严格串行、顺序可预期；不同设备并行、互不阻塞。
- 幂等：调用方提供 `idempotencyKey`。引擎对 `设备|幂等键` 加细粒度锁，
  “去重判定 + 入队”原子；同键并发/重试只执行一次，重复请求恒为
  `ACCEPTED + DUPLICATE_COMMAND`，不产生重复副作用。驱动层另有幂等缓存回放。
- 超时：命令在独立驱动线程执行，工作线程 `join(timeout)` 控制，
  无响应则中断并给出 `TIMEOUT/NO_RESPONSE`。
- 命令风暴（队列打满）按可配置策略处理，**队列有界、禁止无界堆积**：
  - `REJECT`（默认）立即 `STORM_REJECTED`
  - `DELAY` 限时等待空位，超时 `STORM_DELAY_REJECTED`
  - `MERGE` 同设备同命令在队请求就地合并参数，后继命令 `MERGED`，最终只执行一次；
    无可合并对象且队列满时退化为 REJECT

配置项（`application.properties`）：
```
homeauto.engine.per-device-queue-capacity=8
homeauto.engine.backpressure-policy=REJECT     # REJECT / DELAY / MERGE
homeauto.engine.max-delay-millis=2000
homeauto.engine.command-timeout-millis=1500
```

---

## 3. 规则语义（Rule Semantics）

条件（`rule.Condition`，可经 REST JSON 编解码）：
- `state`：单属性 `EQ/NE/GT/LT`；属性缺失时 `EQ=false, NE=true`（确定语义）
- `time`：当日秒时间窗口，`start>end` 表示跨午夜（如 22:00–06:00），左闭右开
- `all / any / not`：组合条件

触发与传播（`RuleEngine`，对每次状态变化做一次 BFS 传播）：
1. 条件不满足 **不得触发**；规则只作用于其所属家庭，且条件必须绑定到事件设备。
2. **一次传播去重**：同一规则在一次事件传播中最多触发一次（全局 fired 集合）。
3. **层内统一冲突裁决**后再下发；动作经每设备串行队列执行，只有**真正生效**的动作
   才产生下一层事件。
4. **成环检测**：维护规则触发路径，候选规则已在当前路径上即判定闭环，
   立即终止整条传播并记录成环规则（首尾相接），可经
   `GET /api/records/cycles/{householdId}` 查询。
5. 规则动作跨家庭时由 `AccessControl` 拒绝（`ACTION_TARGET_OUT_OF_SCOPE`），
   记录原因但不影响其他规则——**系统内部自动化同样不得越界**。
6. 每步评估、裁决依据、下发与终止原因写入 `RuleTrace`（日志可见完整追踪链路）。

---

## 4. 冲突裁决（Conflict Resolution）

同一家庭 + 同一设备 + 同一目标属性（`commandTarget`）的候选进入同一裁决组：

1. 与胜出者**命令相同且参数一致** → 判为重复动作（`DUPLICATE`），合并不下发；
2. 其余互斥指令按 **(priority 降序, ruleId 字典序)** 唯一确定胜者；
3. 被抑制动作写入具体原因（胜出规则 + 优先级依据），可经
   `GET /api/records/suppressions/{ruleId}` 查询。

裁决只依赖规则优先级与 id，**不依赖遍历顺序或时间精度**，结果确定可复现。

---

## 5. 多住户隔离（Access Control）

身份经 `X-Principal-Id` 头传递。所有资源访问先过鉴权，拒绝原因可机读区分：

| DenyReason | 场景 |
|---|---|
| `MISSING_IDENTITY` | 未提供身份头 |
| `UNKNOWN_PRINCIPAL` | 身份不存在 |
| `HOUSEHOLD_MISMATCH` | 跨家庭列举/建规则/查记录 |
| `DEVICE_NOT_AUTHORIZED` | 访问/上报/命令非授权设备 |
| `RULE_NOT_AUTHORIZED` | 访问非授权规则 |
| `ACTION_TARGET_OUT_OF_SCOPE` | 规则动作目标跨家庭（含内部自动化） |

演示身份：`alice`(H1)、`bob`(H2)、`carol`(H1+H2)。

---

## 6. HTTP 入口

| 方法 路径 | 说明 |
|---|---|
| `POST /api/devices/{id}/reports` | 状态上报（body: seq/online/attributes） |
| `GET  /api/devices/{id}` | 设备 + 最新状态快照 + 故障模式 |
| `POST /api/devices/{id}/commands?wait=true` | 命令下发（command/params/idempotencyKey/timeoutMillis） |
| `GET  /api/devices/{id}/commands/{cmdId}` | 查询命令结论 |
| `POST /api/devices/{id}/fault` | 故障注入：HEALTHY/OFFLINE/FAIL_ALWAYS/NO_RESPONSE |
| `PUT/GET/DELETE /api/rules[/{id}]` | 规则管理（条件 JSON 见下） |
| `GET  /api/records?householdId=&deviceId=` | 执行记录（含失败上下文、规则链路） |
| `GET  /api/records/suppressions/{ruleId}` | 被抑制动作原因 |
| `GET  /api/records/cycles/{householdId}` | 最近一次成环路径 |

规则条件 JSON 示例：
```json
{ "type":"all", "children":[
  {"type":"state","device":"thermo-h1","attr":"currentTemp","op":"GT","value":28},
  {"type":"not","children":[{"type":"time","start":0,"end":21600}]}
]}
```

---

## 7. 验证方法

```bash
mvn test            # 全部 12 个测试
mvn spring-boot:run # 本地起服务，可用 curl 按上表操作
```

测试以需求中强调的对抗性场景为主，单测日志打印**关键输入、判定依据与规则追踪链路**：

| 测试类 | 覆盖点 |
|---|---|
| `ConcurrentCommandTest` | 8 并发命令（含 3 个共享幂等键）：串行生效、去重数量确定、无半更新 |
| `OutOfOrderReportTest` | 乱序/重复 seq 不回退、未知能力拒绝、离线上报不覆盖属性 |
| `RuleCycleTest` | A⇄B 成环：快速终止、环路径 [A,B,A] 可查询、各规则只执行一次 |
| `ConflictResolutionTest` | 高/低优先级冲突 + 重复动作：胜者确定、抑制原因可查询、turnOff 不执行 |
| `AccessControlTest` | 6 类越权原因区分、正当跨家庭访问、内部动作不越界 |
| `FailureRecoveryTest` | 离线/执行失败/超时分类、诊断上下文、同键重试无重复副作用 |
| `BackpressureTest` | 有界 REJECT 不越界、MERGE 同类归一、恢复后只执行一次 |
| `ModelCompatibilityTest` / `ModelRegistryTest` | 未知命令/能力/缺参/非法值/版本裁决 |
| `TimeWindowConditionTest` | 时间窗口边界、跨午夜、组合条件、缺失属性确定语义 |

集成测试通过 `TestIsolationSupport` 在每个用例前恢复“演示数据基线”
（清队列/故障/规则/记录/快照并重建在线基线），整套件连续多轮运行结果一致。

## 8. 关键代码索引

- 状态收敛：`engine/StateConvergence.java`
- 命令生命周期：`engine/CommandService.java`
- 每设备串行队列/风暴策略：`engine/PerDeviceCommandQueue.java`
- 模拟硬件/故障注入：`engine/SimulatedDeviceDriver.java`
- 规则传播/去重/环检测：`engine/RuleEngine.java`
- 冲突裁决：`engine/ConflictResolver.java`
- 多住户鉴权：`engine/AccessControl.java`
