# 基于WebRTC的Android屏幕串流（带音频）

这是一个技术研究学习项目，使用了最简单的思路去实现。<br>
内含：Android App、信令服务器、接收推流Web界面

## 项目内容

请使用Android Studio打开此项目

1. 项目根目录：Android App
2. server目录：Ktor实现的信令服务器
3. server/src/main/resources/html/index.html：接收推流Web界面

## 效果

1. 低延迟的屏幕串流，但不是零延迟，延迟会随设备负载和网络情况有所波动
2. 具备设备音频流的传输，仅限Android 10及更高版本
3. Android设备或Web重连恢复

## 运行方法

请确保设备可访问信令服务器，如需配置地址请看后续文档

1. Android App请直接安装到物理机上（虚拟机因GLES的问题无法使用）
2. 信令服务器：打开Android Studio底部Terminal工具，执行
   Linux/macOS：

```shell
cd server
./gradlew run
```

Windows：

```shell
cd server
.\gradlew.bat run
```

3. App中依次点击**启动后台服务**、**申请捕获权限**、**连接信令服务器**按钮，过程中请授予所有申请的权限
4. 访问PC本机 http://127.0.0.1:9999 以访问接收Web界面
5. Web的Video可能因为浏览器策略原因无法自动开始播放，请用鼠标点击播放按钮即可看到实时串流画面

## 配置修改

### 信令服务器地址和端口

1. Android App修改信令服务器地址：修改**MainActivity**的**serverUrl**变量
2. Ktor端口：**server/src/main/kotlin/com/example/Application.kt**中修改
3. Web接收端修改信令服务器地址：**server/src/main/resources/html/index.html**中websockt实例化参数部分

## 遇到问题？

#### 串流一段时间后就完全断了

请在手机设置中允许App完全后台运行或允许高耗电

#### vivo/iQOO设备没有画面

这类设备不打开开发者选项也能进行USB调试，但功能效果受限，请检查是否真的启用了**开发者选项**，及重启设备

#### Web接收端为什么经常不会自动播放串流

浏览器为了避免广告骚扰用户，带声音的视频在一些情况下是不会自动播放的。如果你需要一定自动播放，可以给video标签加上muted（静音），就可以确保自动播放，但声音需要手动打开。

#### top查看App使用CPU高

我已经用工具分析过了，要么就是webrtc的二进制逻辑使用的高，要么就是内容拷贝占用内存和GPU显存间的io消耗，暂时没办法优化，反正手机不会烫

## 暂未解决的问题

1. 在设备高负载时，发生画面抖动的问题（前后帧跳跃）