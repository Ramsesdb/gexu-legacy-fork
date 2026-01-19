<div align="center">

<img src="./logo.png" alt="Gexu logo" title="Gexu logo" width="180"/>

# Gexu

### Geek Nexus: The Intelligent Manga Reader

**Gexu** is a next-generation manga reader powered by **AI and semantic search**. Built on Mihon/Tachiyomi, it features a **state-of-the-art Novel/PDF viewer**, **contextual AI chat with 26 tools (Function Calling)**, **Visual AI selection (Circle-to-Search)**, **hybrid vector search (RAG)**, and **full extension compatibility**.

[![CI](https://img.shields.io/github/actions/workflow/status/Ramsesdb/gexu/build.yml?branch=main&labelColor=27303D)](https://github.com/Ramsesdb/gexu/actions/workflows/build.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/Ramsesdb/gexu?labelColor=27303D&color=0877d2)](/LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/Ramsesdb/gexu?labelColor=27303D&color=06599d)](https://github.com/Ramsesdb/gexu/releases)

</div>

---

## ✨ What Makes Gexu Unique?

**Gexu** adds powerful AI capabilities on top of the solid Mihon foundation. Here is everything new:

### 🤖 AI-Powered Intelligence
- **In-Reader Chat:** Ask questions about the plot, characters, or lore *while reading*, without leaving the page.
- **Visual Intelligence (Circle-to-Search):** Select any part of an image to ask "Who is this?" or "Translate this SFX".
- **26 Smart Tools:** The AI can query your library. Ask: "How many hours have I read this month?", "Show me manga like Solo Leveling", or "What chapters have I bookmarked?".
- **Semantic Search:** Don't remember the title? Describe the plot: *"The one where the guy gets reincarnated as a slime"* and Gexu will find it using local vector embeddings.
- **Anti-Spoiler Engine:** The AI knows exactly where you are in the story and **refuses** to spoil future events.
- **Knowledge Base (RAG):** Indexes your library locally for offline, privacy-first context.
- **Hybrid Embeddings:** Uses Google's Gemini Cloud embeddings (high precision) + MediaPipe On-Device embeddings (offline).

### 📖 Enhanced Reading Experience
- **Novel Mode:** Advanced reader for Light Novels and PDFs.
- **Live OCR:** Extract text from images instantly using Google MLKit.
- **Reading Buddy:** The AI automatically generates summaries of chapters as you read, helping you catch up if you drop a series.
- **PDF Smart Reflow:** Native MuPDF integration allows reading PDF manga/novels with text reflow, TOC navigation, and dark mode.
- **Two-Way Viewing:** Switch between original Pages (Image) and Extracted Text (Reader) mode instantly.

### 📝 Deep Organization
- **Smart Notes:** Attach notes to specific pages or chapters.
- **Tag System:** Categorize notes with tags like `Theory`, `Favorite`, `Question`, `Important`.
- **Global Search:** Search through all your notes and tags instantly.
- **Backup & Restore:** All notes and AI settings are preserved in `.tachibk` backups.

---

## 🚀 Detailed Feature List

| Feature | Description |
|:---|:---|
| **RAG (Retrieval Augmented Generation)** | Local vector database (`manga_embeddings.sq`) stores semantic meaning of your library. |
| **Hybrid Search** | Combines BM25 (keyword) + Vector (semantic) search for 99% accuracy. |
| **Function Calling** | AI can execute 26 distinct functions to interact with the app database. |
| **Response Cache** | Smart LRU cache saves API tokens by storing common answers for 24h. |
| **Multi-Provider** | Support for **Gemini, OpenAI, Claude (Anthropic), OpenRouter**, and Custom endpoints. |
| **Visual Selection** | Integrated cropping tool for multimodal queries. |
| **PDF Table of Contents** | Full support for PDF bookmarks and navigation. |
| **Reading Time Stats** | Tracks actual time spent reading per series. |
| **Tracker Scores** | AI can see your MAL/AniList scores to give better recommendations. |

---

## 🤖 AI Tools Available (26 total)

The AI assistant has access to these tools for querying your library:

| Tool | Description |
|:-----|:------------|
| `get_library_stats` | Library statistics and top genres |
| `search_library` | Search by title, author, or genre |
| `get_full_manga_context` | Complete info about a specific manga |
| `get_reading_time_stats` | **Top manga by reading time** |
| `get_reading_history` | Recent reading activity |
| `get_pending_updates` | Unread chapters waiting |
| `get_tracker_scores` | MAL/AniList scores |
| `find_similar_manga` | Semantic search recommendations |
| `get_completed_series` | Series you've finished |
| `get_dropped_series` | Series you may have abandoned |
| `get_reading_streak` | Your reading streak (consecutive days) |
| `get_genre_breakdown` | Genre percentages in library |
| `get_reading_patterns` | When you read (time of day, day of week) |
| `predict_completion_time` | Estimate when you'll finish a series |
| `get_monthly_summary` | Monthly reading summary |
| `get_notes_by_tag` | Find all notes tagged "Important" or "Theory" |
| `get_categories` | List your library categories |
| ... and 9 more | Full list in `AiToolDefinitions.kt` |

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

- [x] **In-Reader AI Chat**
- [x] **Function Calling (26 tools)**
- [x] **Visual Selection (Circle-to-Search)**
- [x] **Semantic Search (RAG)**
- [x] **Novel/PDF Reader with OCR**
- [x] **Hybrid Embeddings (Cloud + Local)**
- [x] **Response Cache**
- [x] **Advanced Note System**

### Planned 📋

- [ ] **SFW Toggle** — Global NSFW filter with PIN/biometric unlock
- [ ] **TTS Integration** — Text-to-speech for extracted novel text
- [ ] **Quick Actions** — Dynamic contextual suggestions
- [ ] **Feedback Loop** — Thumbs up/down on AI responses

---

## 🛠️ Technical Details

| Property | Value |
|----------|-------|
| **Application ID** | `com.ramsesbr.gexu` |
| **Namespace** | `eu.kanade.tachiyomi` (for extension compatibility) |
| **Language** | Kotlin 100% |
| **UI Framework** | Jetpack Compose + Material 3 |
| **Database** | SQLDelight with SQLite |
| **ML** | MediaPipe (on-device), MLKit (OCR) |

---

## 🤝 Contributing

Pull requests are welcome! Before contributing:
- Read our [Code of Conduct](./CODE_OF_CONDUCT.md)
- Check the [Contributing Guide](./CONTRIBUTING.md)
- Review open [issues](https://github.com/Ramsesdb/gexu/issues)

---

## 💙 Credits

**Gexu** is built on the shoulders of giants:

- **[Mihon](https://mihon.app)** — The upstream foundation
- **[Tachiyomi](https://tachiyomi.org)** — The original reader
- Community forks: [J2K](https://github.com/Jays2Kings/tachiyomiJ2K), [Yōkai](https://github.com/null2264/yokai), [SY](https://github.com/jobobby04/TachiyomiSY), [Komikku](https://github.com/komikku-app/komikku)
- **[Google MediaPipe](https://developers.google.com/mediapipe)** — On-device ML models
- **[MuPDF](https://mupdf.com/)** — Fast PDF rendering engine

---

## 📜 License

```
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2025 Gexu Project

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

<div align="center">

**Made with ❤️ for the manga reading community**

[Report Bug](https://github.com/Ramsesdb/gexu/issues) · [Discussions](https://github.com/Ramsesdb/gexu/discussions)

</div>
