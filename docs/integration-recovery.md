# 集成恢复交接

本文件只记录 MiRing-Integration-Recovery-20261014 的集成恢复，不是新增功能验收。

恢复范围是 INTEGRATION_RECOVERY_MANIFEST.json 的 86 个精确路径，从只读 sourceHead 7fbfbe5b86e0b59b2b6f9471b7773912629f57a6 原字节复制到 integration 基线 f47f15a0edc907dc8271c077d094a83a2f9befea。没有产品逻辑或 UI 修改。

既有证据只引用、不重做：W641、W642、W643、W644 为已验收产品源码成果；W647/VR368 为既有同签名打包证据，见 docs/reviews/L3_I1_PACK_REVIEW.md。该次 APK SHA256 1ec07cf084ddc60fc3843b189cab0e8d331a01aeb9f63c4b0ad2d0f6e359f899 与 RPK SHA256 9654691d80a30a6c0bf1cfe1c992cf15c7b3d0296d1da09f1032a2db2931f3ea 属于旧构建记录，不是本票新产物哈希。

本票只在当前工作区复用 ignored 原签名输入，运行排除 generateDebugSigning 的 Android 全量单测与 assembleDebug，并核对包名与原证书身份。不重签、不安装、不重建 RPK、不重跑真实高德、模拟器或实机研究。集成构建通过不代表完整导航产品或新实机通过。
