<div align="center">

<img src="./.github/assets/logo.png" alt="Gexu logo" title="Gexu logo" width="120"/>

# Gexu

### Geek Nexus: Smart manga reader with AI-powered features

**Gexu** is a SFW-focused fork of Mihon/Tachiyomi with **modern UX**, **tablet-friendly design**, and **intelligent features** (recommendations, Q&A, summaries, semantic search), while maintaining **full compatibility** with extensions and backups.

[![CI](https://img.shields.io/github/actions/workflow/status/Ramsesdb/gexu/build.yml?branch=main&labelColor=27303D)](https://github.com/Ramsesdb/gexu/actions/workflows/build.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/Ramsesdb/gexu?labelColor=27303D&color=0877d2)](/LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/Ramsesdb/gexu?labelColor=27303D&color=06599d)](https://github.com/Ramsesdb/gexu/releases)

</div>

---

## 🎯 Core Principles

- **SFW by default** — Adult content hidden with optional PIN/biometric unlock
- **Full compatibility** — Works with `.tachibk` backups and Mihon/Tachiyomi extensions
- **Performance first** — 60fps scrolling, lazy loading, efficient caching
- **Modern UI** — Clean bottom navigation, tablet-optimized layouts

## 🚀 Roadmap (MVP Features)

- [x] **Rebranding foundation** — New identity, icon, app name
- [ ] **Bottom navigation** — Library / Recents / Updates / More
- [ ] **Enhanced reader** — Dual-page spread support for tablets in landscape
- [ ] **SFW toggle** — Global filter with protected access
- [ ] **Multi-source feed** — Unified updates from 3-5 favorite sources
- [ ] **Multi-source merge** — Combine chapters from different sources for same series
- [ ] **Novel mode** — Text viewer with TTS, customizable typography
- [ ] **AI features** — Recommendations, semantic search, chat Q&A, chapter recaps

## 📦 Download & Install

*Releases coming soon. For now, build from source:*

### Build (Debug)

```bash
./gradlew :app:assembleStandardDebug
adb install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

**Requirements:** Android 8.0+ (API 26) • ~100MB storage

### Build (Release)

```bash
./gradlew :app:assembleStandardRelease
```

---

## 🛠️ Technical Details

| Property | Value |
|----------|-------|
| **Application ID** | `com.ramsesbr.gexu` |
| **Namespace** | `eu.kanade.tachiyomi` (for extension compatibility) |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 35 (Android 15) |
| **Language** | Kotlin 100% |
| **Architecture** | Multi-module (MVVM + Jetpack Compose) |

### Deep Link Schemes

Gexu supports the following URL schemes for compatibility and future features:

- `gexu://` — Primary scheme
- `tachiyomi://` — Legacy compatibility
- `mihon://` — Upstream compatibility

## 🔒 SFW Stance

Gexu hides adult/NSFW content by default. Users can optionally enable it with PIN or biometric authentication in future releases. This makes Gexu suitable for general audiences while respecting user choice.

## 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.

Before contributing:
- Read our [Code of Conduct](./CODE_OF_CONDUCT.md)
- Check the [Contributing Guide](./CONTRIBUTING.md)
- Review open [issues](https://github.com/Ramsesdb/gexu/issues)

## 💙 Credits

**Gexu** is built on the shoulders of giants:

- **[Mihon](https://mihon.app)** — The upstream foundation
- **[Tachiyomi](https://tachiyomi.org)** — The original reader
- Community forks: [J2K](https://github.com/Jays2Kings/tachiyomiJ2K), [Yōkai](https://github.com/null2264/yokai), [SY](https://github.com/jobobby04/TachiyomiSY), [Komikku](https://github.com/komikku-app/komikku)

Thank you to all contributors who make the manga reader ecosystem thrive! 🙏

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
