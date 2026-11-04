# 洋哥做题了

面向家庭的 Android 学习辅助应用。家长通过局域网网页管理儿童手机的使用时间与答题任务，并可按教材章节生成和管理题目。

## 功能

- 设置使用时长、应用白名单与黑名单，查看应用使用时间。
- 手动弹题、定时弹题、定时刷题、50 题练习和家长放行。
- 使用通义千问按教材章节生成题目，支持图片、WPS 文档导入。
- 在家长网页上传 Markdown 教材知识库，按年级、学科、册别和章节管理。
- 查看答题记录和错题，题目不足时后台补充。

AI 功能需要自行配置阿里云百炼 API Key，并按服务商规则计费。AI 生成的题目可能出错，请结合实际学习情况检查。

## 组成

- `app/`：儿童端 Android 应用及其提供的局域网家长网页。
- `parent-app/`：家长端 Android 外壳。
- `photo-app/`：独立拍题应用。
- `bundle-installer/`：组合安装器。
- `tools/`：辅助整理资料的脚本。

## 使用

儿童端需要 Android 8.0 或更高版本。安装后设置家长密码，并根据应用内提示授予使用情况访问、悬浮窗等所需权限；可选权限由家长自行开启。家长设备与儿童手机连接同一局域网，在儿童端的家长设置中查看网页地址或二维码。

桌面图标默认隐藏。全新安装后，可通过 ADB 打开设置页：

```sh
adb shell am start -n cn.tan.watchgate/.MainActivity
```

在家长设置中配置百炼 API Key 后，可以从家长网页生成题目或导入教材知识库。家长网页通过 HTTP 在局域网提供服务，请仅在可信网络使用，不要直接暴露到互联网。

## 构建

需要 JDK 17–21、Android SDK Platform 34、Build Tools 34.0.0、Python 3 和 `zip`。脚本默认在 macOS 常见位置寻找 SDK 和 JDK；也可以设置 `WATCHGATE_ANDROID_SDK`、`WATCHGATE_JAVA_ROOT`。

```sh
# 构建儿童端；首次运行时生成本地签名密钥
bash build.sh

# 构建家长端，并将其加入儿童端的下载资源
bash parent-app/build.sh
bash build.sh

# 可选：构建儿童端、家长端、拍题应用和组合安装器
bash build-bundle.sh
```

默认输出到 `releases/`。`.signing/` 中的本地签名密钥是私密文件，请妥善备份并勿提交。不同签名构建的 APK 通常不能覆盖安装原有版本；卸载可能清除本机数据，请先做好备份。

## 限制

普通 Android 权限不能保证防止卸载、强行停止、清除数据或撤销权限；后台运行和悬浮窗行为也可能受厂商系统限制。使用时间是系统前台记录的估算。AI 题目的正确性和教材覆盖范围需要使用者自行判断。

## 许可证与第三方内容

项目原创源码按 [MIT License](LICENSE) 发布。`app/src/main/java/io/nayuki/qrcodegen/` 使用 Project Nayuki 的二维码生成库（MIT），原版权和许可证声明保留在各源码文件中。

教材 Markdown、题目、图片等内容的权利可能与源码不同；源码的 MIT 许可不自动授予第三方教材或素材的再分发权。`tools/build_libremath_knowledge.py` 处理 LibreMath 资料时注明了来源及 CC BY 4.0，使用生成内容时请遵循其标注的许可与署名要求。
