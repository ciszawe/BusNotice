# 通勤胶囊 CommuteCapsule

公交通勤助手（Android 16 / ColorOS 16 流体云）。v0.2：Compose 界面、线路/站点搜索、多段换乘行程、自定义通勤车、省电定位。

- 出发时间窗前用精确闹钟唤醒，开定位前台服务预热 GPS（不常驻后台）
- 每 20 秒查询车来了实时公交，按步行时间算「几分钟后出门」
- 用 Android 16 实时通知（promoted ongoing）显示在流体云胶囊
- 每次轮询记录到 polls.csv，后续用于学习步行时间和到站误差

构建：推送到 GitHub 后 Actions 自动出 APK，在 Releases 下载。
数据源为车来了非官方接口，可能随时失效。
