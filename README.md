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
4. **成环检测（以“条件是否仍成立”为准，而非是否回指同一设备）**：维护规则触发
   路径，候选规则先按当前状态评估条件——**只有条件确实成立、会真的再次触发的
   回指规则才构成成环**（首尾相接，立即终止整条传播并记录环规则，可经
   `GET /api/records/cycles/{householdId}` 查询）；条件已不再成立的回指规则
   不算环，链路必须继续把后续影响推完。
   - 例：窗帘开 -> 开灯 -> 关窗帘 -> 上锁。关窗帘事件回指到“窗帘开则开灯”，
     但此时窗帘已是 closed、该规则条件不再成立，故不判环，上锁规则照常触发。
   - 真正的闭环（窗帘开 -> 开灯 -> 再开窗帘，回指规则条件仍成立）依旧被识别
     并终止，环路径形如 `[A,B,A]`，首尾相接的闭合规则清晰可见，不会无限传播。
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
| `POST /api/sandbox/dry-runs` | 试运行/复盘推演（见第 7 节） |
| `GET  /api/sandbox/dry-runs[/{id}]` | 查询试运行报告（按家庭隔离） |

规则条件 JSON 示例：
```json
{ "type":"all", "children":[
  {"type":"state","device":"thermo-h1","attr":"currentTemp","op":"GT","value":28},
  {"type":"not","children":[{"type":"time","start":0,"end":21600}]}
]}
```

---

## 7. 试运行 / 复盘沙箱（Dry-Run Sandbox）

在真正动设备之前，用 `POST /api/sandbox/dry-runs` 对一条新规则或一段历史片段做完整推演。
请求体指定**家庭**、**用于条件判断的时间点** `evaluationTime`（时间窗口条件统一按该时刻评估），
以及一组**设备状态变化片段**（可手工构造，也可以是整理过的历史片段，允许乱序、重复、离线、
包含能力范围之外的内容，按给定顺序逐条收敛）：

```json
{
  "householdId": "H1",
  "evaluationTime": 1790395200000,
  "fragments": [
    {"deviceId":"thermo-h1", "seq":10, "online":true, "attributes":{"currentTemp":35}},
    {"deviceId":"curtain-h1", "seq":11, "online":false, "attributes":{}}
  ],
  "limits": {"maxDepth": 16, "maxEvents": 256, "maxSteps": 500}
}
```

返回结构化、可查询的报告（`DryRunReport`）：

| 字段 | 含义 |
|---|---|
| `fragments[]` | 每个片段的收敛结论（accepted/rejectReason/detail/appliedSeq） |
| `steps[]` | 每一步判定依据：条件评估结果、冲突裁决、动作结论、成环、截断原因 |
| `firedRules[]` | 最终触发的规则（同一次状态变化同一规则不会重复出现） |
| `suppressedActions` | 被抑制/拒绝的动作及确定原因（CONFLICT/DUPLICATE/SCOPE_DENIED 等） |
| `actions[]` | 每个胜出动作的推演结论（EFFECTIVE/REJECTED/FAILED/TIMEOUT + reason） |
| `cyclePath` | 真实成环时首尾相接的规则链；非成环为 null |
| `expectedStates` | 推演后家庭内每台设备的预期在线位与能力属性 |
| `terminationReason` | COMPLETED / MAX_DEPTH_EXCEEDED / MAX_EVENTS_EXCEEDED |
| `stepsTruncated` | 仅返回条目达到上限（推演本身仍推完） |

### 7.1 适用场景

- **新规则上线前**：用假设片段验证它会与哪些现有规则联动、是否成环、是否抑制别的动作。
- **历史复盘**：把历史上的状态变化（含乱序/重复/离线片段）重新喂入，解释当时“会发生什么”。
- **容量规划**：观察链路层数与访问节点数，评估规则拓扑的传播规模。

### 7.2 与真实执行的语义一致性

沙箱不是另一套近似实现，而是复用/镜像真实组件的同一条语义路径：

- 片段收敛逐行镜像 `StateConvergence`：未知能力拒绝、缺必填字段拒绝、`seq` 单调
  （较旧/重复片段不采纳、**已推演生效的状态不会被旧值改回**）、离线只翻在线位不覆盖属性；
- 规则评估、一次传播去重、层级推进、路径成环判定镜像 `RuleEngine`：先评估条件，
  条件不满足不触发；**只有条件确实成立、会真的再次触发的回指规则才判成环并终止
  该次传播**（条件已不再成立的回指不算环，回指之后的规则继续触发）；非成环但
  链路较长时继续推完后续影响；
- 同一输入在试运行与真实上报下结论一致：该触发的规则都触发、每条规则在同一次
  状态变化中只执行一次、动作结论与设备最终预期状态逐项对得上（真实成环的识别与
  快速终止两边也一致，见 `BackrefConditionCycleTest` 对同一份片段的双侧断言）；
- 冲突裁决调用 `ConflictResolver.resolvePure`（与真实裁决相同的纯函数），
  胜出结果只取决于 (priority 降序, ruleId 字典序)，与片段/线程处理顺序无关；
- 动作校验复用 `CommandService.validateAgainstModel`（未知命令/缺参/非法值/未知能力）
  与 `AccessControl.requireRuleActionScope`（跨家庭目标确定拒绝，不静默忽略）；
- 设备不可用结论与真实一致：模拟世界在线位为离线或注入 OFFLINE -> `FAILED/DEVICE_OFFLINE`，
  FAIL_ALWAYS -> `FAILED/EXECUTION_FAILED`，NO_RESPONSE -> `TIMEOUT`，绝不假装成功；
- 判定时间在整场推演中固定为 `evaluationTime`；相同输入（含相同判定时间、相同规则/设备/
  故障/基线指纹）产生相同 `dryRunId` 与相同报告，重复推演幂等回放。

### 7.3 隔离范围

- 推演在**方法局部的模拟世界**（`DryRunService.SimWorld`）中进行：不调用真实驱动、
  不进入每设备命令队列、不修改 `StateConvergence`、不写 `ExecutionLog`、
  不写真实成环记录（`RuleEngine.lastCycle`）；
- 裁决走纯函数入口，不触碰 `ConflictResolver` 的共享抑制表；
- 报告存入独立的有界存储 `DryRunReportStore`（按家庭隔离，访问同样鉴权），
  与正式执行记录物理分离；
- 沙箱无任何实例可变状态：多份试运行彼此独立，可与真实命令、真实上报并发执行，
  结果互不影响（见并发隔离单测）。

### 7.4 容量边界

`limits` 可逐请求收紧（不能放宽到配置默认值之上，配置项见
`homeauto.engine.dry-run-max-*`）：

| 上限 | 到达时行为 |
|---|---|
| `maxDepth`（默认 16） | 终止推演，`terminationReason=MAX_DEPTH_EXCEEDED`，返回已得部分结果 |
| `maxEvents`（默认 256） | 终止推演，`MAX_EVENTS_EXCEEDED`，返回已得部分结果 |
| `maxSteps`（默认 500） | 只截断 `steps[]` 返回条目并置 `stepsTruncated=true`，推演继续推完 |

成环终止是正常语义（`COMPLETED` + `cyclePath`），与容量截断可明确区分：
回指同一设备但回指规则条件已不再成立时**既不报环也不触发**，长链继续推完；
只有条件仍成立、真实再触发的闭环才置 `cyclePath`；层数/节点数超限则置
`MAX_DEPTH_EXCEEDED` / `MAX_EVENTS_EXCEEDED` 并返回已得到的部分结果——
非成环长链与容量截断都不会被误报为成环。
报告存储本身按家庭有界（最近 100 份），不会无界占用内存。

---

## 8. 验证方法

```bash
mvn test            # 全部 20 个测试
mvn spring-boot:run # 本地起服务，可用 curl 按上表操作
```

测试以需求中强调的对抗性场景为主，单测日志打印**关键输入、判定依据与规则追踪链路**：

| 测试类 | 覆盖点 |
|---|---|
| `ConcurrentCommandTest` | 8 并发命令（含 3 个共享幂等键）：串行生效、去重数量确定、无半更新 |
| `OutOfOrderReportTest` | 乱序/重复 seq 不回退、未知能力拒绝、离线上报不覆盖属性 |
| `RuleCycleTest` | A⇄B 成环：快速终止、环路径 [A,B,A] 可查询、各规则只执行一次 |
| `BackrefConditionCycleTest` | 回指同一设备但回指规则条件已不再成立的非成环长链（窗帘开→开灯→关窗帘→上锁）：后续规则完整触发、无成环结论、预期状态正确；试运行与真实上报双侧一致、同输入（含相同判定时间）重复推演报告完全相同；真实成环仍被双侧识别并终止、环路径首尾相接 |
| `ConflictResolutionTest` | 高/低优先级冲突 + 重复动作：胜者确定、抑制原因可查询、turnOff 不执行 |
| `AccessControlTest` | 6 类越权原因区分、正当跨家庭访问、内部动作不越界 |
| `FailureRecoveryTest` | 离线/执行失败/超时分类、诊断上下文、同键重试无重复副作用 |
| `BackpressureTest` | 有界 REJECT 不越界、MERGE 同类归一、恢复后只执行一次 |
| `ModelCompatibilityTest` / `ModelRegistryTest` | 未知命令/能力/缺参/非法值/版本裁决 |
| `TimeWindowConditionTest` | 时间窗口边界、跨午夜、组合条件、缺失属性确定语义 |
| `DryRunSandboxTest` | 试运行/复盘：乱序重复片段收敛、真实成环、含时间条件的非成环长链路、与真实命令并发隔离与可重复、容量截断、离线/非法值/跨家庭/冲突语义一致 |

集成测试通过 `TestIsolationSupport` 在每个用例前恢复“演示数据基线”
（清队列/故障/规则/记录/试运行报告/快照并重建在线基线），整套件连续多轮运行结果一致。

## 9. 关键代码索引

- 状态收敛：`engine/StateConvergence.java`
- 命令生命周期：`engine/CommandService.java`
- 每设备串行队列/风暴策略：`engine/PerDeviceCommandQueue.java`
- 模拟硬件/故障注入：`engine/SimulatedDeviceDriver.java`
- 规则传播/去重/环检测：`engine/RuleEngine.java`
- 冲突裁决（含纯函数入口 resolvePure）：`engine/ConflictResolver.java`
- 多住户鉴权：`engine/AccessControl.java`
- 试运行/复盘沙箱：`engine/DryRunService.java`（+ `DryRunReport` / `DryRunReportStore` / `api/SandboxController`）
