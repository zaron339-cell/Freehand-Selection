# Freehand Selection · 圈选

**Circle anything on your Android screen and ask AI about it.**

Translate a passage, understand a diagram, or ask follow-up questions while reading PDFs, browsing the web, or reviewing notes. Draw around content with your finger or stylus, confirm the selection, and read the response in a floating window.

[简体中文](README.md) · **English**

## Features

- **Freehand screenshot selection** — crop along the actual path, including concave shapes. Areas outside the selection are filled white; only the confirmed crop is sent.
- **No Root, Shizuku, or accessibility service required** — uses Android screen capture consent and overlay permission. Works with fingers and ordinary styluses, without Samsung SDK or S Pen features.
- **Bring your own API key (BYOK)** — supports OpenAI Chat Completions-compatible APIs, native Gemini, and Anthropic Messages. Save and switch between multiple configurations.
- **Local user memory** — keep response preferences and learning-related notes on your device. Review, edit, delete, or disable automatic extraction.
- **Automatic long-chat summaries** — condense older context while keeping the full conversation history locally.
- **Floating conversations** — move and resize the response window, read streaming Markdown, copy answers, stop or retry requests, and resume saved chats.

## Get started

Requires **Android 8.0 (API 26) or later** and a model that accepts images.

1. Download and install the [0.5.0 debug APK](https://github.com/zaron339-cell/Freehand-Selection/raw/refs/heads/main/dist/freehand-selection-0.5.0-debug.apk). This is the debug build provided in the repository.
2. Open model settings (「AI 模型设置」). Choose a preset or create a configuration with your own API key, model ID, and endpoint. Use the image connection test, then save.
3. Enable the floating button (「开启悬浮球」) and grant overlay and screen capture permissions when prompted.
4. Switch to your content, tap the floating button, and draw around the area you want to ask about.
5. Optionally enter an instruction such as “translate this,” then tap confirm (「确认」). Continue asking questions in the response window.

You can also try selection and chat on the in-app practice page. Model services may charge for usage; availability and quotas depend on the provider. The English README does not imply an English app interface.

## Your data

Conversation history, selection images, and user memory are stored in the app's private directory. API keys are encrypted using Android Keystore. There is no developer-operated AI backend: requests go directly to your configured endpoint.

**Local memory does not mean offline inference.** Chat sends the selection image and necessary conversation context to your model service. Memory extraction and conversation summaries also call that service and consume API usage. Memory extraction does not upload additional screenshots. You can disable automatic extraction or the use of memory in responses.

## Current limitations

- Screen capture requires system consent. Protected content may appear black, and device background restrictions can affect overlays.
- Screenshot chat needs an image-capable model using one of the three supported protocols. Responses-only endpoints and text-only models are not directly supported.
- No built-in web search. LaTeX formulas currently appear as source text.
- Compatibility with other device brands, split-screen, foldables, and external displays still needs device testing.

## Documentation and development

- [Detailed user and developer guide (Chinese)](docs/USER_GUIDE.zh-CN.md): model setup, memory, window controls, network behavior, and build commands.
- [Validation record (Chinese)](VALIDATION.md): test coverage and limitations. Local mock API tests do not establish real model quality.
- [Repository About settings](docs/REPOSITORY_ABOUT.md): suggested description and topics.

Built with Kotlin, Android MediaProjection, and native overlay windows. Builds use JDK 17; see the detailed guide for toolchain requirements and limitations.
