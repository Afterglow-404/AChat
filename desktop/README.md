# Wisp Desktop

Wisp Desktop 是 Wisp 的 Windows 桌面伴侣，用于调试手机端的人工回复、贴纸、工具、语音和 TTS 链路。

## 开发启动

在 `WeChatClone/desktop` 目录安装依赖：

```powershell
npm install
npm.cmd start
```

桌面端会自动启动 `scripts/wisp-dev-server.mjs`，开启交互式回复模式，创建本机随机 Dashboard Token，并在退出时关闭子服务。

如果外接显卡导致 Electron 启动崩溃，可以使用 GPU 兼容模式：

```powershell
npm.cmd run start:compat
```

切换到核显 780M 后，可以尝试完整沙箱模式：

```powershell
npm.cmd run start:secure
```

手机端仍然连接电脑上的 Wisp 服务地址。桌面端默认只监听 `127.0.0.1`，用于本机控制；需要手机访问时，应单独启动开发服务并设置 `WISP_DEBUG_HOST=0.0.0.0`，或后续增加“局域网共享”开关。

## Windows 打包

```powershell
npm run dist
```

产物为 portable 版本，后续可以再增加安装包、自动更新和开机启动。
