# 开发规范（当前阶段）

## 指令优先级（必须遵守）

1. 用户当轮明确指令 > 本文件规则 > 默认工程习惯。
2. 若存在冲突：先停止实现，先给出冲突点，再按用户最新指令执行。
3. 未经用户确认，禁止扩大改动范围（只改当前问题直接相关文件与逻辑）。
4. 每次做完之后，使用askQuestions询问用户继续优化点位，给出方案选择。
5. 本项目为此测试功能，改动有更合理、更优质的方案请提出，若要改动不考虑历史兼容，以最新为准，**但不可缺失已实现的功能**。

## Android 构建阶段边界（必须遵守）

1. 修改以下脚本前，先阅读目标脚本开头的 `Build Plan Contract` / `Stage Contract` 注释，并把该注释视为当前实现边界：
	`scripts/resolve-tavern-build-plan.sh`、`scripts/build-tavern-android-runtime-image.sh`、`scripts/build-tavern-dependency-packs.sh`、`scripts/sync-tavern-android-bootstrap.sh`、`scripts/build-tavern-android-apk.sh`、`scripts/build-tavern-android-local.sh`。
2. Stage 1 只负责 runtime image/rootfs；不得构建 dependency packs、server source、server payload、APK。
3. Stage 2 只负责 dependency packs；不得构建 runtime image、server source、server payload、APK。
4. Stage 3 只负责 Tavern server source；不得引入 dependency packs，不得生成最终 server-payload，不得组装 APK。
5. Stage 4 是唯一允许组合最终 server-payload 并组装 APK 的阶段；但不得隐式回补 stage 1、2、3。
6. `build-tavern-android-local.sh` 是正常本地一键入口，只允许编排 4 个阶段，不允许偷偷塞入额外构建逻辑导致 CI/本地语义分叉。
7. 若用户明确要求调整阶段边界，先同步修改对应脚本头部契约与 `README.md` 的“四阶段边界”章节，再改实现；禁止只改代码不改边界文档。

## 代码规范（必须遵守）
1. 任何时候都要保持代码的可读性和可维护性，遵循 DRY 原则，避免重复代码。
2. 任何时候都要保持代码的健壮性和稳定性，遵循 KISS 原则，避免过度设计。
3. 任何时候都要保持代码的安全性和隐私保护，遵循 YAGNI 原则，避免过度收集和暴露用户数据。
4. 任何时候都要保持代码的性能和效率，遵循 SOLID 原则，避免过度耦合和依赖。
5. 任何时候都要保持代码的测试覆盖率和质量，遵循 TDD 原则，避免缺乏测试和文档。
6. 任何时候都要保持代码的版本控制和协作，遵循 Git Flow 工作流，避免混乱和冲突。
7. 任何时候都要保持代码的持续集成和部署，遵循 CI/CD 流程，避免手动操作和错误。
8. 任何时候都要保持代码的用户体验和界面设计，遵循 UX/UI 原则，避免不友好和混乱的界面。









