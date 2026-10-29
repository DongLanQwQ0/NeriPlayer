# 下游构建规则

- 自动 APK 构建仅由上游 **已发布的 Release** 驱动。定时任务只查询 Release 列表；没有待处理 Release 时，合并、构建、签名和发布均跳过。
- 上游普通提交、下游 push 和 PR 均不自动构建。Android 检查、Native 检查与网站部署仅保留手动入口；原有工作流启用／停用状态保持不变。
- 上游 Release 按标签固定到提交后合并，成功才运行定向检查、优化构建、独立证书签名和下游发布。草稿和已完整发布的适配版本不重复构建。
- 合并后、推送前校验触发规则，防止上游工作流重新引入 push／PR 自动构建。真正的源码冲突继续中止，不用全局 `ours`／`theirs` 丢弃修改。
- Release API 的临时断连及 HTTP 429、500、502、503、504 最多尝试三次；权限错误立即失败。

## 2026-10-29 失败调查

最近四次失败（`40749505768`、`40786381419`、`40809212418`、`40855272399`）均在合并上游 Release `NeriPlayer-15fb25d8.10282107` 时发生冲突：

- `app/build.gradle.kts`
- `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/service/presentation/AndroidPlaybackServicePresentationPort.kt`

这些运行未进入 APK 构建与签名阶段。该 Release 仍待人工合并适配；修改工作流触发规则不会解决源码冲突。

较早一次 `40397314192` 在查询 Release API 时出现 `RemoteDisconnected`。已为此路径增加有上限的重试。
