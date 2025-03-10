# 本地应用防火墙

这是一个 Android 应用防火墙，可以选择本机安装的应用程序，并通过 VPN 拦截其网络访问。

## 功能特点

- 列出设备上安装的所有应用程序
- 可选择显示/隐藏系统应用
- 选择需要拦截网络访问的应用
- 通过 VPN 服务拦截选定应用的网络流量
- 简洁直观的用户界面

## 技术实现

- 使用 Android VpnService API 实现网络流量拦截
- 使用 Jetpack Compose 构建现代化 UI
- 使用协程处理异步操作
- 使用 SharedPreferences 存储应用设置

## 使用方法

1. 启动应用后，会看到已安装的应用列表
2. 勾选需要拦截网络访问的应用
3. 打开顶部的防火墙开关，授予 VPN 权限
4. 防火墙启动后，被选中的应用将无法访问网络

## 权限说明

应用需要以下权限：

- `INTERNET`: 用于建立 VPN 连接
- `ACCESS_NETWORK_STATE`: 用于监控网络状态
- `FOREGROUND_SERVICE`: 用于在后台运行 VPN 服务
- `POST_NOTIFICATIONS`: 用于显示服务通知
- `QUERY_ALL_PACKAGES`: 用于获取已安装的应用列表

## 注意事项

- 应用需要 Android 7.0 (API 24)或更高版本
- 启用防火墙需要授予 VPN 权限
- 防火墙启用后，会在通知栏显示一个持久通知
- 当前版本会完全阻断选定应用的所有网络流量

## 开发者信息

这个应用是作为 Android 开发学习项目创建的，展示了如何使用 VpnService API 实现网络流量控制。

## 许可证

[MIT License](LICENSE)
