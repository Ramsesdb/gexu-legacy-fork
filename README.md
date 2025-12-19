<div align="center">

<img src="./logo.png" alt="Gexu logo" title="Gexu logo" width="180"/>

# Gexu

### Geek Nexus: The Intelligent Manga Reader

**Gexu** is a next-generation manga reader powered by **AI and semantic search**. Built on Mihon/Tachiyomi, it features a **state-of-the-art Novel/PDF viewer**, **contextual AI chat**, **hybrid vector search (RAG)**, and **full extension compatibility**.

[![CI](https://img.shields.io/github/actions/workflow/status/Ramsesdb/gexu/build.yml?branch=main&labelColor=27303D)](https://github.com/Ramsesdb/gexu/actions/workflows/build.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/Ramsesdb/gexu?labelColor=27303D&color=0877d2)](/LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/Ramsesdb/gexu?labelColor=27303D&color=06599d)](https://github.com/Ramsesdb/gexu/releases)

</div>

---

## ✨ What Makes Gexu Different?

### 🤖 AI-Powered Reading Experience

- **Contextual AI Chat:** Ask questions about your current manga/novel while reading
- **Semantic Library Search:** Find manga by describing scenes or themes ("Find the one where the MC fights a dragon")
- **Anti-Spoiler Mode:** AI respects your reading progress (won't spoil future chapters)
- **RAG (Retrieval-Augmented Generation):** Local vector store with hybrid BM25 re-ranking
- **Multi-Provider Support:** OpenAI, Gemini, Claude, Anthropic, OpenRouter, or custom endpoints
- **Hybrid Embeddings:** Cloud (Gemini 768-dim) + Local (MediaPipe USE 100-dim) for offline capability

### 📖 Advanced Novel/PDF Reader

- **Intelligent OCR:** Extract text from manga pages using Google MLKit
- **PDF Support:** Native rendering with MuPDF (fast, with reflow and table of contents)
- **Hybrid Reading Mode:** Toggle between images and extracted text on-the-fly
- **Two Reading Directions:** Vertical scroll or horizontal page-flip (book mode)
- **Smart Text Extraction:** Prioritized OCR based on current reading position
- **Customizable Typography:** Font size, themes (Dark/Light/Sepia/System), line height

### 🔧 Built on Solid Foundations

- **100% Mihon/Tachiyomi Compatible:** Works with all extensions and `.tachibk` backups
- **Jetpack Compose UI:** Modern, Material 3 design with smooth 60fps scrolling
- **Clean Architecture:** Multi-module MVVM with SQLDelight, Coroutines, and Dependency Injection
- **Efficient Caching:** Disk + memory caching with LRU eviction for images and embeddings

---

## 🚀 Key Features

| Category | Features |
|----------|----------|
| **AI/RAG** | • Semantic search (10K+ vector cache)<br>• Hybrid embedding (cloud + local)<br>• BM25 re-ranking (70% vector + 30% keyword)<br>• Context-aware prompts with reading history |
| **Reader** | • OCR text extraction (MLKit)<br>• PDF rendering (MuPDF)<br>• Vertical scroll / Book flip modes<br>• Customizable themes & fonts |
| **Library** | • Extension compatibility (Mihon/Tachiyomi)<br>• Backup/restore (`.tachibk`)<br>• Fast image loading (Coil 3)<br>• SQLite with efficient indexing |
| **Tech Stack** | • Kotlin 100%<br>• Jetpack Compose<br>• SQLDelight<br>• MediaPipe (on-device ML) |

---

## 📦 Download & Install

### Latest Release

Check the [Releases](https://github.com/Ramsesdb/gexu/releases) page for the latest APK.

### Build from Source

```bash
# Debug build
./gradlew :app:assembleStandardDebug

# Install to device
adb install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

**Requirements:** Android 8.0+ (API 26) • ~120MB storage • ARM64 architecture

---

## 🎯 Roadmap

### Implemented ✅

- [x] **AI Chat Integration** — Ask questions about your current manga/novel
- [x] **Semantic Search** — Find manga by description, not just title
- [x] **Novel/PDF Reader** — OCR, reflow, customizable themes
- [x] **Hybrid Embeddings** — Cloud + local for offline capability
- [x] **Extension Compatibility** — Full Mihon/Tachiyomi support

### In Progress 🚧

- [ ] **Visual AI (Multimodal)** — Send manga panels to AI for translation/explanation
- [ ] **SFW Toggle** — Global NSFW filter with PIN/biometric unlock
- [ ] **TTS Integration** — Text-to-speech for extracted novel text

### Planned 📋

- [ ] **Auto-Summaries** — AI-generated chapter recaps after long breaks
- [ ] **Smart Recommendations** — Personalized suggestions based on reading history
- [ ] **Multi-Source Merge** — Combine chapters from different sources for the same series

---

## 🛠️ Technical Details

| Property | Value |
|----------|-------|
| **Application ID** | `com.ramsesbr.gexu` |
| **Namespace** | `eu.kanade.tachiyomi` (for extension compatibility) |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 35 (Android 15) |
| **Language** | Kotlin 100% |
| **Architecture** | Multi-module (MVVM + Clean Architecture) |
| **UI Framework** | Jetpack Compose + Material 3 |
| **Database** | SQLDelight with SQLite |
| **ML** | MediaPipe (on-device), MLKit (OCR) |

### Deep Link Schemes

- `gexu://` — Primary scheme
- `tachiyomi://` — Legacy compatibility
- `mihon://` — Upstream compatibility

---

## 📚 Documentation

- [AI Implementation Summary](./AI_IMPLEMENTATION_SUMMARY.md) — Deep dive into RAG architecture
- [Vision Document](./VISION.md) — Long-term goals and philosophy
- [Build Commands](./BUILD_COMMANDS.md) — Detailed build instructions
- [Changelog](./GEXU_CHANGELOG.md) — Version history

---

## 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.

Before contributing:
- Read our [Code of Conduct](./CODE_OF_CONDUCT.md)
- Check the [Contributing Guide](./CONTRIBUTING.md)
- Review open [issues](https://github.com/Ramsesdb/gexu/issues)
- Follow the [CI/CD Rules](./CI_CD_RULES.md) for code formatting

---

## 💙 Credits

**Gexu** is built on the shoulders of giants:

- **[Mihon](https://mihon.app)** — The upstream foundation
- **[Tachiyomi](https://tachiyomi.org)** — The original reader
- Community forks: [J2K](https://github.com/Jays2Kings/tachiyomiJ2K), [Yōkai](https://github.com/null2264/yokai), [SY](https://github.com/jobobby04/TachiyomiSY), [Komikku](https://github.com/komikku-app/komikku)
- **[Google MediaPipe](https://developers.google.com/mediapipe)** — On-device ML models
- **[MuPDF](https://mupdf.com/)** — Fast PDF rendering engine

Thank you to all contributors who make the manga reader ecosystem thrive! 🙏

---

## 📜 License

```
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2024 Gexu Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

<div align="center">

**Made with ❤️ for the manga reading community**

[Report Bug](https://github.com/Ramsesdb/gexu/issues) · [Request Feature](https://github.com/Ramsesdb/gexu/issues) · [Discussions](https://github.com/Ramsesdb/gexu/discussions)

</div>
