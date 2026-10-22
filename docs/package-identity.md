# 签名打包身份核验报告

本报告由 `scripts/verify-package-identity.py` 默认模式覆盖生成，机器记录段只用本轮计算值，不含手抄旧文档哈希。

## 机器记录

```
baseline=dc70a4280ba9df65d56a08c0890227447b491c51
head=dc70a4280ba9df65d56a08c0890227447b491c51
build_started_unix=1792613785.569525
icon_count=14
jks_sha256=1ff650dc756372107cd51bb73a683fbe53a36fd4c3308986de1ade2aaf2746a1
cert_der_sha256=b5e1f4b324081de1c9e6abc5662685f5dc00f19bd646b1eaa49ce7987ae5eb1e
gradle_argv=java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest assembleDebug -x generateDebugSigning --no-daemon
gradle_exit=0
apk_path=app\build\outputs\apk\debug\app-debug.apk
apk_bytes=11637509
apk_sha256=4fa382198217546aec7b9cdeec4b875c0497ce7a1a6a8cbdfb1d64ed184a2221
application_id=com.minavigatering.probe
apk_version_code=1
apk_version_name=0.1.0
apk_cert_sha256=b5e1f4b324081de1c9e6abc5662685f5dc00f19bd646b1eaa49ce7987ae5eb1e
baseline_apk_path=C:\program1\MiNavigateRing\deliverables\MiNavigateRing-Probe-0.1.0.apk
baseline_apk_sha256=f516de2e28a14c2338a376f2ae97f0ea37f9e4a7bca7bd68c1d1e297b4b4bc31
baseline_apk_cert_sha256=b5e1f4b324081de1c9e6abc5662685f5dc00f19bd646b1eaa49ce7987ae5eb1e
rpk_argv=node scripts/build-rpk.js
rpk_exit=0
rpk_path=band-app\dist\com.minavigatering.probe.debug.0.2.0-navigation.rpk
rpk_bytes=78749
rpk_sha256=3081423e5c8ec4e13271e6227097061543aa6edb34c05346f8279702dc106843
rpk_package=com.minavigatering.probe
rpk_version_name=0.2.0-navigation
rpk_version_code=11
rpk_contains_project_cert=true
```

## 固定状态

```
signature_build=verified
node_rpk_simulation=not_run
device_verification=unverified
qemu_firmware=unverified
amap_live=unverified
watchface_background=unverified
physical_icons=unverified
install_performed=no
```

## 说明

本票只验证签名构建身份：`signature_build=verified` 表示 certificate.pem 的 DER SHA-256、debug.jks 的 SHA-256、新构建 APK 的签名证书以及已安装基线 APK 的签名证书四者一致，且 APK 通过 `apksigner verify --print-certs`（退出码 0）。

未运行 `band-app/test/check-built-rpk.js` 或任何宿主/QEMU 模拟，`node_rpk_simulation=not_run`；Node 侧 RPK 构建只证明 RPK 字节包含同一项目证书 DER，其模拟结果不能当作固件或真机通过。

未连接设备，未执行 `adb install`、`adb shell` 或 BLE，`install_performed=no`、`device_verification=unverified`。

原生 QEMU/固件兼容（`qemu_firmware=unverified`）、真实高德 （`amap_live=unverified`）、回表盘/长期后台（`watchface_background=unverified`）、物理图标可见性（`physical_icons=unverified`）均为 unverified。

安装前的新包模拟验证门槛由 L3 后续独立检查，打包完成不豁免此门槛。

`baseline_apk_sha256` 是定位已安装基线的定位值，不是本票 APK 或 RPK 的哈希；本票产物身份以上表中的 `apk_sha256` 与 `rpk_sha256` 为准。
