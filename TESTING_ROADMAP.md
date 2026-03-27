# Testing Roadmap

这个文档不是测试规范大全，只是说明 Silk 的自动化测试要先把哪些东西做成 harness，先拦什么，再往哪里扩。

核心思路参考 OpenAI 那篇 harness engineering：重点不是“多写点测试”本身，而是把环境、约束、反馈回路、仓库内知识做成机器能直接消费的东西。先让系统可验证，再谈自动 merge。

## 现在的判断

这个项目不适合一上来追求“全量自动化”。

更现实的顺序是：

1. 先把高风险路径做成稳定、快速、低噪声的 PR gate。
2. 再把重任务拆到 nightly。
3. 最后才考虑 auto-merge。

也就是说，先做 harness，再做 autonomy。

## 现在已经有的 harness

- [x] 后端 contract test 基座
  - 隔离数据库、聊天历史、上传目录
  - 用 Ktor test host 直接打真实路由
- [x] 后端核心回归覆盖
  - 注册 / 登录
  - 建群 / 加群
  - 联系人请求 / 私聊创建
  - 发消息 / 未读数
  - 撤回消息
  - 文件上传 / 下载 round trip
- [x] shared 模块基础测试
  - 序列化
  - i18n 文案与占位符
  - 上海时区格式化
- [x] web smoke 测试
  - markdown / math 归一化
  - production bundle 构建
- [x] Fast CI 已拆成 3 个 job
  - `backend-contract`
  - `shared-desktop`
  - `web-smoke`
- [x] 已在 `origin` 做过红绿验证
  - 故意打坏后端逻辑，CI 会红
  - 故意打坏 web markdown 归一化，CI 会红
  - 修回来后，CI 会绿

## 现在这个 gate 实际能拦什么

- 后端 API 合同被改坏
- 群邀请码逻辑回归
- 非作者撤回消息权限回归
- shared 模型 / 文案 / 占位符漂移
- Web 数学 markdown 修复被改坏
- Web 构建链路挂掉

这还不是“测试体系完成”，但已经不是占位测试了，已经开始能挡真实回归。

## Fast CI 该保持什么风格

Fast CI 只做三件事：

1. 高信号
   - 失败了就大概率真有问题
2. 可重复
   - 不依赖本机脏状态，不依赖手工步骤
3. 快
   - 适合每个 PR 都跑

不符合这三条的任务，原则上不要硬塞进 PR gate。

## 下一步，按顺序做

### 1. 把 Fast CI 变成真正的门禁

- [ ] 把当前 Fast CI 配成 `develop` 的 required checks
- [ ] 等它稳定一段时间，再谈 auto-merge

这里不要反过来。没有稳定门禁，auto-merge 只是更快地把 bug 合进去。

### 2. 把“规则”继续编码，而不是写在人脑里

现在测试主要挡业务回归，后面要继续把一些工程约束做成机械检查：

- [ ] 新增后端路由必须带 contract test
- [ ] 修 bug 必须补 regression test
- [ ] 继续收敛硬编码路径 / 环境依赖
- [ ] 能做成结构检查的东西，尽量不要留给 code review 口头提醒

这一块对应的其实不是“多写文档”，而是把文档里稳定的规则变成 lint / test / CI。

### 3. 补 nightly，而不是污染 Fast CI

这些应该单开 workflow：

- [ ] Android build / unit test
- [ ] Harmony build 验证
- [ ] Weaviate 集成测试
- [ ] WebSocket 多用户场景
- [ ] 更完整的端到端聊天主链路

原因很简单：这些任务重、慢、环境依赖多，不适合做每个 PR 的快速反馈。

### 4. 开始做 repo 内的 system of record

OpenAI 那篇文章里一个很重要的点是：仓库内知识要成为 system of record，而不是把关键信息散落在聊天、口头约定、临时记忆里。

对 Silk 来说，后面建议逐步补这些东西：

- [ ] 一个很短的 `AGENTS.md` / 工程入口文档
- [ ] 一个明确的 testing / harness 文档
- [ ] 技术债和执行计划放进 repo，而不是只存在对话里

不是为了“写文档而写文档”，而是为了让 agent 和人都能在 repo 里找到同一份规则。

## 暂时不放进 Fast CI 的东西

下面这些目前先别做成 PR 必过：

- Android 运行时测试
- Harmony 运行时测试
- Weaviate 真实服务联调
- `silk.sh deploy/start/stop` 全链路 smoke
- 大而重的浏览器 E2E
- bundle size / 性能预算门禁

不是不重要，而是现在放进去，噪声和维护成本会先把门禁本身搞坏。

## 这个 roadmap 的完成标准

做到下面这几个点，才算进入下一阶段：

- Fast CI 是稳定的 required check
- bug 修复开始系统性补 regression test
- nightly 接住多端和外部服务
- 关键规则开始从“知道”变成“被检查”
- auto-merge 建立在稳定 harness 上，而不是建立在乐观上

## 一句话总结

Silk 现在要做的不是“把所有测试都加上”，而是先把最值钱、最稳定、最能拦真实回归的那部分 harness 做厚；等这个底座稳了，再逐步把多端、外部服务、部署链路接进来。
