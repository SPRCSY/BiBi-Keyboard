# SM8750 SenseVoice QNN 构建

本分支针对骁龙 8 至尊版 SM8750 / Hexagon HTP V79，只生成 arm64-v8a APK。
沿用本 fork 的签名、保活改进、录音、Ten VAD、伪流式预览与输入法上屏管道。
不是独立 daemon，不向上游自动提交或合并。

## 运行时与模型

`.github/qnn-sm8750.lock.json` 固定 sherpa-onnx 1.13.4、官方 QNN 示例 APK 和
SM8750 5 秒 SenseVoice context binary 包，并校验下载文件的 SHA-256。
CI 执行 `python3 .github/scripts/prepare_qnn_sm8750.py`，生成忽略跟踪的
`app/qnn-runtime/`，不把数百 MB 的模型或库提交到 Git。

普通 Release 未提供独立 QNN AAR；本分支从官方发布的同版本 QNN Android 示例 APK
提取 JNI、ONNX Runtime 和 HTP/V79 库。APK 仅用作 ZIP 容器，不安装、不执行。
原 AAR 仅提供经 SHA-256 固定的 VAD 等 Kotlin API 类，原 CPU JNI 不再参与打包。
注意：同为 1.13.4 的原 AAR 与 QNN APK 的离线配置接口并不完全一致；真机发现
QNN JNI 还读取 Moonshine 的 decoder、qnnConfig 字段。离线识别接口改用
`app/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt` 中的源码，基于官方
v1.13.4 API 并按固定 APK 的 DEX 字段补齐；结果 durations 使用 FloatArray。
准备脚本剔除原 JAR 中对应类，避免重复类。QnnJniSchemaTest 防止该故障回归，
Apache-2.0 许可见 third_party/sherpa-onnx/LICENSE。
ONNX Runtime 保留给 Ten VAD、降噪及其他已有 CPU 功能；SenseVoice 固定为
`provider=qnn`、`backendLib=libQnnHtp.so`、SM8750 `model.bin`，没有 CPU 回退。

库清单：`libsherpa-onnx-jni.so`、`libonnxruntime.so`、`libQnnHtp.so`、
`libQnnSystem.so`、`libQnnHtpPrepare.so`、`libQnnHtpV79Stub.so`、
`libQnnHtpV79Skel.so`。Skel 是 DSP ELF，不应当作 ARM64 库重新编译或替换。
通过 `useLegacyPackaging=true` 将库解压到 nativeLibraryDir，设置 ADSP 搜索路径，
并按官方 Android 示例声明 `libcdsprpc.so` 的访问。

模型首次推理时从 APK assets 校验并原子复制到 noBackupFilesDir，避免主线程安装
大模型，也避免把可重新生成的模型放入系统备份。配套许可证及元信息随模型打包。
源项目已下载的 CPU 模型不会被使用，也不会擅自删除用户现有文件。

## 输入与判停

- 首次运行此分支时默认选 SenseVoice、关闭备用识别、开启伪流式和静音判停；
  后续启动尊重用户修改。保活开关仍由用户控制。
- 输入为 16 kHz 单声道 PCM；每次送入 80,000 样本，不足 5 秒补零。
- 长音频复用 Ten VAD 的静音区间切分；没有停顿的连续语音使用短重叠，合并文本时
  只对实际重叠的窗口去重，不会把 5 秒后的音频直接截掉。
- 长按键盘麦克风不再获取自动判停抑制令牌，按原有静音时长和灵敏度设置自动停止。
  松手时已停止的录音不会重新开始。外部语音 API 的独立抑制策略保持原样。
- 固定窗口接缝与预览准确率仍需真机语料验证；不能仅凭成功打包宣称 NPU 加速。

## GitHub Actions

使用现有 **Release on Version Bump** → Run workflow，选择此分支，
保持 `publish_release=false`，下载签名 APK 与 SHA256SUMS.txt。
使用已有四项签名 Secrets。实际工程要求 Java 21 / Gradle 9.4.1，沿用 Java 21，
而非降为交接说明中的 Java 17。仅使用预编译产物，不需要本地或云端编译 NDK/QNN SDK。
构建版本为 `4.5.2-qnn-sm8750` / versionCode 179，可覆盖本 fork 原密钥签名的 178 版。

## 真机验收

1. 在 SM8750 手机上安装 APK，首次启动后检查 SenseVoice 已选中，授予录音权限。
2. 断网录制中文、英文、短句、超过 5 秒的长句，以及持续 20 秒无停顿语音。
3. 选择长按模式，按住说话后保持静音，确认未松手也会停止并上屏；松手不取消结果。
4. 读取 `adb logcat -s BiBiQnn sherpa-onnx`，实际录制后必须同时看到：
   - `QNN_CONFIG provider=qnn backend=libQnnHtp.so ... htp=V79 fallback=disabled`
   - `QNN_INITIALIZED provider=qnn backend=HTP htp=V79`
   - `QNN_INFERENCE_COMPLETE provider=qnn backend=HTP ... elapsedMs=...`
   - 原生 QNN 的 context binary 初始化信息，没有 `QNN SetupBackend failed` 等错误。
5. 仅有 CONFIG 表示尝试配置，不能证明推理完成；必须实际识别并出现 COMPLETE。
   不支持的芯片或库缺失会报错，不能当作 CPU 成功路径。
6. 同一段语料分别在原 CPU APK 与 QNN APK 上测试冷启动、热推理耗时、长句准确率，
   并以相同录音时长、屏幕亮度、网络和温度条件比较耗电。没有实测前不承诺速度或功耗提升。

## 验证命令

```
python .github/scripts/test_qnn_artifact_setup.py
python .github/scripts/test_release_workflow.py  # 需 PyYAML 和 bash
./gradlew :app:testDebugUnitTest --tests '*QnnAudioWindowsTest' --tests '*QnnBuildDefaultsTest'
./gradlew :app:assembleRelease -PabiFilters=arm64-v8a
```

官方依据与原始产物：
- https://k2-fsa.github.io/sherpa/onnx/qnn/build-android-demo.html
- https://k2-fsa.github.io/sherpa/onnx/android/apk-qnn-simulate-streaming-asr.html
- https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn-binary
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.4/sherpa-onnx/csrc/qnn/offline-sense-voice-model-qnn.cc
