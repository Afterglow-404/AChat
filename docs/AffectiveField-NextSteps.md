# AffectiveField 下一阶段计划

## 当前完成

- Android 端 P0 最小闭环：AffectiveField、PendingEvents、RhythmSensor、ResponseAssessment。
- eventId 幂等链路。
- Desktop 关系状态页和 Wisp Server 快照接口。
- Android 到 Desktop 的显式同步开关。

## P0.1 真实联调

目标：证明手机、服务端、Desktop 三端数据一致。

验收标准：

1. 在 DebugPage 开启 `AffectiveField Desktop sync`。
2. 填写 Desktop 局域网地址，例如 `http://192.168.x.x:17890`。
3. 手机发送一轮普通消息，Desktop 关系页出现对应角色和 eventId。
4. 重复提交同一个 eventId，Desktop 事件日志不增加重复记录。
5. 关闭 Wisp Desktop 后，手机聊天仍能正常返回，只有同步日志记录失败。

## P1.1 响应时间线

记录每轮：用户消息摘要、AI 回复摘要、ResponseAssessment、场变化、PendingEvent 创建/收尾结果。

Desktop 增加按角色和时间筛选的时间线，用于定位“用户问了什么、AI 是否回答、关系场为何变化”。默认只保存在 Wisp Server 内存中，不写入云端。

## P1.2 ProactiveScheduler 干跑

先实现只读 dry-run：根据 anticipation、PendingEvents 和冷却时间计算“是否建议主动联系”，但不真正发送消息。

必须具备：

- quiet hours；
- 每角色冷却时间；
- 最近主动消息上限；
- 触发原因和决策日志；
- Desktop 手动批准后才允许发送。

## P1.3 群聊关系隔离

群聊不共享单一 AffectiveField，至少拆分为：

- 用户与角色 A；
- 用户与角色 B；
- 角色 A 与角色 B；
- 群聊整体氛围。

先在数据模型和 Desktop 调试视图中完成隔离，再接入群聊回复策略。

## P1.4 语音和关系状态联动

将关系状态转换为低风险的表现参数，例如语速、停顿、语气指令和是否使用贴纸。不允许直接把关系状态映射为心理诊断或高风险行为。

GPT-SoVITS 和 Qwen3-TTS 继续使用角色独立音色配置；情绪只作为表现层参数，不改变角色音色身份。

## 暂缓项目

- Polyvagal 三态作为真实神经状态判断；
- 孤独风险和 AI 依赖度评分；
- 自动依恋损伤修复；
- SelfConcept 和复杂 VirtualBody；
- 无用户确认的主动消息发送。

