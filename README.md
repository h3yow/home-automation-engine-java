# Home Automation Engine（本地可运行的智能家居自动化与设备状态协同引擎）

用本地模拟设备替代真实硬件，提供**设备状态上报、命令下发与状态收敛、自动化规则触发、
冲突裁决、失败恢复、多住户隔离与背压保护**。所有入口（HTTP、本地 Facade、系统自动化）
收敛到同一套判定逻辑，结论一致且可解释。

## 1. 运行与验证

```bash
# 编译 + 全部测试（31 个：状态收敛/规则成环/冲突裁决/越权/并发/模型版本/HTTP）
mvn test

# 启动本地服务（默认 8080，自动播种两个家庭的演示数据）
mvn spring-boot:run
# 关闭演示数据：-Dha.demo.seed=false
```

演示住户（`DemoDataSeeder`）：

| 住户 | 家庭 | 角色 | 设备 |
|---|---|---|---|
| alice | home-a | OWNER | plug-a1、lamp-a1 |
| bob | home-a | MEMBER | 同上（可写） |
| carol | home-a | VIEWER | 同上（只读） |
| dave | home-b | OWNER | plug-b1 |

内置规则 `rule-lamp-off`：plug-a1 断电 → lamp-a1 关。

快速体验：

```bash
# 命令下发（住户命令）
curl -s localhost:8080/api/ha/commands -H 'Content-Type: application/json' \
  -d '{"residentId":"alice","deviceId":"plug-a1","command":"setPower","params":{"value":true}}'
# 状态上报（设备入口）
curl -s localhost:8080/api/ha/reports -H 'Content-Type: application/json' \
  -d '{"deviceId":"plug-a1","version":1,"properties":{"power":true}}'
# 规则追踪链路 / 审计记录 / 设备状态
curl -s 'localhost:8080/api/ha/trace?residentId=alice'
curl -s 'localhost:8080/api/ha/logs?residentId=alice'
curl -s 'localhost:8080/api/ha/devices/plug-a1/state?residentId=alice'
# 新建规则（条件 JSON 结构见 §4）
curl -s localhost:8080/api/ha/rules -H 'Content-Type: application/json' -d '{...}'
```

## 2. 状态模型与命令收敛

### 2.1 命令五态（`CommandOutcome`，严格可区分）

| 状态 | 含义 | 何时产生 |
|---|---|---|
| `ACCEPTED` | 已受理，入队尚未执行 | 校验/授权/背压全部通过 |
| `EFFECTIVE` | 已生效，状态已收敛到目标 | 模拟设备成功且 `StateService.confirmEffect` 接受 |
| `REJECTED` | 已拒绝（未执行），`rejectReason` 给原因 | 设备不存在、跨家庭、角色不足、命令不支持、缺字段、非法载荷、离线、队列满、冲突抑制、成环终止、乱序上报 |
| `TIMEOUT` | 设备在时限内无响应 | 执行等待超过 `ha.command-timeout`（默认 5s，测试 800ms） |
| `FAILED` | 设备执行了但返回失败 | 模拟器失败计数、执行异常、生效确认被拒 |

**离线与失败绝不记为成功**：TIMEOUT/FAILED 不推进目标属性，审计中保留失败上下文
（命令 ID、规则 ID、原因文本、耗时）。

### 2.2 状态收敛规则（`StateService`）

- 每个设备一把可重入锁（striped lock）：上报、生效确认串行化，**禁止半更新**。
- 状态带单调递增 `version`：
  - `incomingVersion <= currentVersion` → `REJECTED/STALE_REPORT`，**重复/乱序上报不能让已生效状态无解释回退**；
  - 旧固件上报不带版本号 → 服务端确定性赋 `current+1`（心跳语义）。
  - 版本前进但属性无差异 → 接受为心跳，**不产生事件、不触发规则**。
- 生效确认按 `commandId` 幂等：重复确认不重复写状态、不重复触发规则链，
  **重试不产生重复副作用**。
- 只有“真实属性变化”才生成 `StateEvent`（携带 `causedByCommandId`/`propagationId`，
  供规则链精确关联，避免并发设备交错时的误归属）。
- 事件回调在设备锁**外**派发，避免规则链跨设备时与设备串行区循环等待。

### 2.3 命令通道并发模型（`CommandGateway`）

- 每设备一条 FIFO 队列 + 一个“排空链”（draining 标志 CAS 驱动）：
  **同一设备命令严格按入队顺序执行**；不同设备在共享有界线程池上并行。
- 终态只允许从 ACCEPTED 迁移一次；相同 `commandId`（重试/合并）共享同一终态。
- 审计**先落库后放行** dispatch 等待者，保证“dispatch 返回即可查到记录”。

## 3. 规则语义（`RuleEngine`）

### 3.1 结构

- `Rule(ruleId, homeId, priority, enabled, condition, actions[])`
- 条件（`RuleCondition` 密封类型）：
  - `StatePredicate(deviceId, property, operator, expected)`：`deviceId=null` 表示触发设备自身；跨家庭谓词拒绝读取；
  - `TimeWindow(startMinuteOfDay, endMinuteOfDay)`：半开 `[start,end)`，**支持跨午夜**（22:00–06:00），`start==end` 为空窗口；
  - `Composite(AND|OR, children)`：短路、可嵌套。
- 动作 `RuleAction(targetDeviceId, command, params)`，只允许落在规则所属家庭内。

### 3.2 触发与传播

- **触发范围**：规则只由其条件实际监控的设备的状态变化触发（`watchedDevices`）。
  别的设备变化时即使条件恰好成立也不触发——触发必须可归因到被监控状态。
- **条件不满足绝不触发**；求值只读已提交快照，不读半更新。
- 一次状态变化 = 一次**传播（Propagation）**，在专属工作线程上逐跳推进：
  - 队列中的每个事件只处理一次 → 同一状态变化不会重复执行动作；
  - 规则允许在链路上因“新的状态变化”再次命中，但用**父规则路径快照**做环检测；
  - 成环：命中规则已在当前路径中 → 在派发动作**之前**终止，
    `RuleTrace.cycle=true`，`cyclePath` 给出成环规则序列（首尾规则同名标出闭合点）。
- 静默协议：事件入队与“在途动作保留”在同一把锁内联合维护，
  工作线程仅在“无在途动作且队列空”时结束传播，**无零宽竞态窗口**。

### 3.3 规则追踪链路（`RuleTrace`，可查询）

`GET /api/ha/trace` 或 `facade.lastTrace(homeId)` 返回：
`evaluatedRuleIds / firedRuleIds / suppressed[]{ruleId,deviceId,command,reason,detail}
/cyclePath / cycle / detail`，单测日志打印关键输入、判定依据与完整链路。

## 4. 冲突裁决策略（`ConflictResolver`）

同一传播跳内，多规则对同一设备给出指令时，按以下**全序**裁决，
结果**不依赖遍历顺序或时间精度**（决胜不使用任何时间戳）：

1. 按目标设备分组；同设备上按“命令 + 归一化参数”划分子组；
2. 互斥（命令文本/参数不同，或不同规则给出同效果指令）时全组只留一个胜出者：
   **`priority` 数值小者胜 → 相同 priority 取 `ruleId` 字典序小者 →
   同规则多动作取声明顺序靠前者（actionIndex）**；
3. 其余候选全部标记 `CONFLICT_SUPPRESSED`，`detail` 明确指出胜出规则、优先级与动作；
4. 胜出列表按设备 ID 排序输出，可复现。

被抑制动作可经 `RuleTrace.suppressed` 与审计记录查询到**具体原因与胜出者**。

规则条件 JSON（HTTP `POST /api/ha/rules`）：

```json
{
  "residentId": "alice", "ruleId": "night-lamp", "name": "夜间断电开灯",
  "priority": 10, "enabled": true,
  "condition": {"type":"and","children":[
    {"type":"state","deviceId":"plug-a1","property":"power","operator":"EQ","value":false},
    {"type":"time","start":"22:00","end":"06:00"}]},
  "actions":[{"targetDeviceId":"lamp-a1","command":"setPower","params":{"value":true}}]
}
```

## 5. 模型版本差异（`DeviceModel` / `ModelValidator`）

- **缺失字段 → 确定默认值**：设备注册时用模型 `propertyDefaults` 填充初始状态；
  命令参数缺省时用 `commandParamDefaults` 补齐。默认值键支持命名空间
  `"<command>.<param>"`（如 `setPower.value` 为 Boolean、`setBrightness.value` 为 Integer），
  避免不同命令同名参数类型冲突。
- **未知能力/未知属性/未知参数 → `UNKNOWN_CAPABILITY` 明确拒绝整次请求**，绝不静默忽略。
- **未支持命令 → `UNSUPPORTED_COMMAND`**；**必填缺失 → `MISSING_REQUIRED_FIELD`**；
  **类型非法（数值/布尔族不兼容）→ `INVALID_PAYLOAD`**，绝不按错误语义执行。

## 6. 多住户隔离（`AccessControl`）

- 资源（设备/规则/记录）按 `homeId` 隔离：跨家庭 → `CROSS_HOME_ACCESS`；
  同家庭角色不足 → `INSUFFICIENT_ROLE`，两类原因可区分。
- 角色：OWNER（管理规则）、MEMBER（可写命令）、VIEWER（只读）。
- 命令自带 `homeId` 会与设备绑定家庭强校验，**伪造归属不能绕过边界**。
- **系统内部自动化动作不享有特权**：规则家庭 ≠ 目标设备家庭时
  由 `AutomationDispatcher` 判 `CROSS_HOME_ACCESS` 并留痕，规则条件层也拒绝跨家庭读设备。
- 审计/追踪查询只返回本家庭数据。

## 7. 背压与失败恢复（`BackpressurePolicy`）

按设备配置容量 `ha.backpressure.max-queue-per-device`（默认 8），三种可配置模式：

- `REJECT`（默认）：队列满立即 `BACKPRESSURE_REJECTED`，**禁止无界堆积**；
- `DELAY`：最多等待 `delay-millis` 争取空位，仍满则拒绝（不无限等待）；
- `COALESCE`：同设备同命令同归一化参数的在途请求合并、共享终态。

命令风暴下“生效数 = 状态版本推进数”，重复/合并不产生重复副作用；
失败上下文（原因、规则链、命令 ID、耗时）全部进入有界家庭审计环形存储。

## 8. 代码结构

```
model/      领域模型与枚举（状态、命令、规则、事件、追踪、拒绝原因）
engine/     DeviceRegistry StateService CommandGateway RuleEngine ConflictResolver
            RuleEvaluator ModelValidator AccessControl BackpressurePolicy AuditLog
            HomeAutomationFacade（统一入口）
sim/        DeviceSimulator（本地模拟设备：在线/延迟/失败可配置）
api/        HomeController + ConditionParser（REST 入口）
config/     DemoDataSeeder
tests/      TestKit（手工真实装配）+ 六组重点测试
```

## 9. 验证方法（测试矩阵）

| 测试类 | 覆盖点 |
|---|---|
| `StateConvergenceTest` | 五态、乱序/重复 STALE 拒绝、失败/超时/离线不记成功、幂等不重复、模型默认值 |
| `RuleEngineTest` | 条件不满足不触发、一次变化只执行一次、链式触发、**6 规则接力成环检测与路径**、跨午夜时间窗口 |
| `ConflictArbitrationTest` | 优先级裁决、同优先级 ruleId 决胜、**20 次乱序输入结论恒定**、端到端抑制原因可查 |
| `AccessControlTest` | 跨家庭、角色不足、伪造归属、**自动化越界留痕**、审计家庭隔离 |
| `ConcurrencyTest` | 12 线程并发同设备（无丢失/无重复/版本=生效数）、FIFO 顺序、COALESCE 合并 |
| `ModelVersionTest` | 新旧固件默认值、未知能力/命令/参数、必填缺失、类型不兼容 |
| `HttpIntegrationTest` | REST 入口五态、越权 403、乱序上报、trace 查询，与本地入口结论一致 |

单测日志统一以 `[STATE]/[CMD]/[RULE]/[SIM]/[AUTO]/[TEST]` 前缀打印
**关键输入、判定依据与规则追踪链路**，失败原因均可区分、可诊断。
