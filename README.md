# YOLOCam Android

一加 Ace 6T 定制的实时物体检测 App (YOLOv8n)，与 iOS v3 功能一致。

## 特性
- 竖屏锁定
- CameraX 相机，默认 4K 预览
- YOLOv8n TFLite (FP16) + GPU delegate，推理目标 60fps
- letterbox 全画面预处理 (与 iOS 一致，无裁剪)
- 绿色检测框 + 英文标签 + 置信度
- 左上显示 FPS 和目标数

## 一加 Ace 6T 适配
- 骁龙8 Gen 5 (Adreno 840): TFLite GPU delegate 加速
- 6.83" 165Hz OLED: 高刷渲染
- IMX906 主摄: 4K 预览
- 只打 arm64-v8a 包

## 构建
GitHub Actions `Build APK` 工作流，一键构建 Debug APK。
