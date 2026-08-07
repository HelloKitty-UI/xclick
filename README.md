# 通用按键点击器 (XClick) — LSPosed 模块

APK: `xclick.apk`（由 GitHub Actions 在打 `v*` tag 时自动构建并发布到 Release）

## 功能
- 配置多组「应用 + 触发按键 + 目标 view」，按键触发时自动点击该 view 的隐藏元素（收起控制条时也能点中）
- 系统级附加功能：`rotate_270` 开关 —— 应用/视频请求横屏时强制 ROTATION_270（充电口朝上）
- 全局开关实时生效（切换「保存全部」后无需重启）

## 使用说明（xclick 应用内配置）
- 打开 xclick → 编辑/新建配置页
- **应用包名**：生效应用，如 `com.bilibili.app.in`
- **触发按键**：数字键码或名字，如 `24` / `VOLUME_UP` / `VOLUME_DOWN`
- **view id**：要点击的 view 的 id，如 `gemini_halfscreen_expand`；填对 id 后隐藏的按钮也能点中
- **子文本（可选）**：view 内再点命中文本的那部分，如 `共.*条回复`，留空点整个 view

## LSPosed 启用
1. 安装 `xclick.apk`，在 LSPosed 模块里启用「通用按键点击器」
2. 勾选作用域：
   - 目标应用（如 bilibili）
   - **系统框架**（包名 `android`，使 rotate_270 生效）
3. 重启

## 全局配置项（xclick 应用内，保存全部生效）
- `debounce_ms`：触发防抖
- `consume_key`：触发后是否吞掉按键
- `rotate_270`：1 = 横屏强制 270°，0 = 系统默认（实时生效）

## 构建
```bash
gradle :xclick:assembleRelease
```