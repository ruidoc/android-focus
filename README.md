# 应用防火墙 (ADProject)

这是一个 Android 防火墙应用，主要功能是通过 VPN 服务拦截特定应用的网络流量或视频流量。

## 项目结构

项目采用 MVVM 架构模式组织代码，使用 Jetpack Compose 实现用户界面。以下是主要目录结构及其功能：

### 1. service/ - 服务相关

- `FirewallVpnService.kt` - VPN 服务，负责拦截和处理网络流量
- `FloatingWindowService.kt` - 悬浮窗服务，提供应用悬浮控制界面

### 2. util/ - 工具类

- `FirewallManager.kt` - 防火墙管理器，处理应用拦截规则和视频流量检测
- `VpnStatusTracker.kt` - VPN 状态跟踪器，监控 VPN 服务的运行状态
- `AppUtils.kt` - 通用工具类

### 3. model/ - 数据模型

- `AppInfo.kt` - 应用信息模型类，存储应用的基本信息

### 4. ui/ - 用户界面

- **screens/** - 各个屏幕界面
- **components/** - UI 组件
- **theme/** - 应用主题样式

### 5. viewmodel/ - 视图模型

- `FirewallViewModel.kt` - 防火墙视图模型，连接 UI 和数据层

### 6. receiver/ - 广播接收器

- `ServiceMonitorReceiver.kt` - 服务监控接收器，监控服务状态
- `BootReceiver.kt` - 开机启动接收器，处理设备启动时的操作

### 7. widget/ - 桌面小部件

- `FirewallWidgetProvider.kt` - 防火墙小部件提供者，提供桌面小部件功能

### 8. navigation/ - 导航

- `AppNavigation.kt` - 应用导航控制，管理应用内的页面导航

### 9. 主要文件

- `MainActivity.kt` - 主活动，应用的入口点
- `ADApplication.kt` - 应用类，全局应用配置和初始化

## 核心功能

应用的核心功能在`FirewallVpnService.kt`中实现，通过 VPN 接口拦截和分析网络流量，根据规则决定是否允许流量通过。`FirewallManager.kt`负责管理拦截规则和检测视频流量。

主要功能包括：

- 拦截特定应用的所有网络流量
- 拦截所有应用的视频流量
- 通过流量特征分析识别视频内容
- 提供桌面小部件和悬浮窗快速控制
- 开机自启动和服务保活机制

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
