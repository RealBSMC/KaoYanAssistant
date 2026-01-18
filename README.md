# KaoyanAssistant (Android)

这是一个安卓学习助手应用，包含聊天、文档解析以及本地/远程向量检索（RAG）流程。

## 运行环境

- Android Studio 或 Android SDK + JDK 17
- Android SDK Platform 36
- NDK + CMake（用于原生 `llama.cpp` 构建）
- 仅支持 Arm64 设备或 Arm64 模拟器（不支持 x86/x86_64）

## 编译

```bash
./gradlew assembleDebug
```

## 本地向量模型（可选）

应用可使用本地 GGUF 向量模型。由于模型体积较大，仓库不会提交该文件，
并已在 `.gitignore` 中忽略。

1) 从 Hugging Face 下载兼容的 GGUF 向量模型（推荐 Qwen3-Embedding-4B 的 Q4 量化，
   文件名通常为 `qwen3-embedding-4b-q4_k_m.gguf`）。
   参考搜索入口：`https://huggingface.co/models?search=Qwen3-Embedding-4B%20GGUF`
2) 放置到以下路径：

`app/src/main/assets/models/qwen3-embedding-4b-q4_k_m.gguf`

如果你使用了不同的文件名或路径，请修改：

`app/src/main/java/com/example/kaoyanassistant/services/LocalEmbeddingService.kt`

将其中的 `ModelAssetPath` 与 `ModelFileName` 改为你的实际文件。

若本地模型不可用，应用会自动回退到设置页中的远程向量配置。
