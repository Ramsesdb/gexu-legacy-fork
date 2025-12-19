# Gexu Vision & Roadmap

## What is Gexu?

**Gexu** = **Geek Nexus** — A nexus point where manga, manhwa, novels, and AI-powered features converge.

### In One Sentence
A Mihon-based reader that's more elegant, SFW by default, and enhanced with an AI brain around your library.

---

## 🎯 Product Vision

### 1. Professional Manga/Manhwa Reader

#### Modern UI
- **Bottom navigation:** Library / Recents / Updates / More
- **Unified screens:** Clean Recents/Updates/History views
- **Tablet-optimized:** First-class experience on large screens

#### Enhanced Reader
- [x] **Dual-page spreads:** Proper pairing in landscape mode on tablets
- [x] **Multiple modes:** Webtoon / horizontal / vertical, all polished
- [x] **Performance:** Smooth 60fps scrolling with lazy loading
- [x] **PDF Support:** Native PDF reading with progressive loading
- [x] **Visual OCR:** Extract text from manga/PDF for translation or TTS

#### Novel Reader (Advanced)
- [x] **Hybrid Content Engine:** Supports extraction from HTML (Readability4J), Images (OCR), and PDF (MuPDF).
- [x] **PDF Reflow:** Dynamically reflows PDF text with custom fonts and sizing (MuPDF integration).
- [x] **Lazy OCR Strategy:** Prioritized text extraction centered on user's current page with background processing (Google ML Kit).
- [x] **Text Mode Toggle:** Instantly switch between original page images and extracted/reflowed text.
- [x] **Customization:** Full typography control (Fonts, margins, spacing, themes).
- [ ] **TTS integration:** Built-in text-to-speech with play/pause controls

### 2. Content Control & Curation

#### SFW Toggle
- **Default:** SFW = true (safe for work/general audiences)
- **Hidden content:** +18 content hidden in Library and Explore
- **Protected access:** Optional PIN/biometric unlock to reveal NSFW

#### Hidden Categories
- **Category privacy:** Flag categories as "hidden"
- **Protected view:** Optional PIN/biometric access to hidden categories

#### Library Cleanup
- **Duplicate detection:** Find duplicate series and aliases
- **Orphan detection:** Identify abandoned/dead series
- **Merge assistance:** AI-assisted suggestions for merge/deletion

### 3. Intelligent Multi-Source

#### Multi-Source Feed
- **Favorite sources:** Select 3-5 preferred sources
- **Parallel loading:** Fetch `latestUpdates` from all sources simultaneously
- **Smart merging:** Combine and sort by recency
- **Efficient caching:** TTL-based cache with manual refresh (pull-to-refresh)
- **Background updates:** WorkManager for periodic updates


### 4. AI-Powered Reading Assistant (Gexu AI)

A privacy-focused, client-side AI architecture where users bring their own API keys (BYOK). No central backend required.

#### Core Features (Implemented)

**Deep Reader Integration**
- **In-Reader Chat:** Available directly inside `ReaderActivity` and `NovelViewer` via overlay.
- **Input Isolation:** Custom `dispatchKeyEvent` logic to prevent keyboard typing from triggering reader navigation (Next/Prev chapter).
- **Context Injection:** AI receives Series Title, Author, and current Chapter context.

**Private & Flexible**
- **Client-Side Architecture:** No backend required. Direct connection to Gemini/OpenAI/Claude.
- **Stateless Operation:** Conversation history lives in memory; Keys stored in encrypted preferences.

#### Supported Providers
- **Google Gemini:** Optimized implementation (Native)
- **OpenAI:** Standard chat completion support
- **Anthropic:** Claude support
- **OpenRouter:** Access to uncensored/open-source models
- **Local/Custom:** Connect to Ollama or compatible endpoints

#### Roadmap Features

**Semantic Search (Local RAG)**
- Index your library locally using vector embeddings (SQLite-vss or pure Kotlin).
- Search by plot points ("finding a lost sword", "protagonist is a necromancer").
- Natural language queries without sending data to a server.

**Visual Understanding (Multimodal)**
- **Feature:** Send current page/panel to Vision Models (Gemini Flash).
- **Use Case:** "Translate this bubble", "Explain this joke", "Who is this character?".

#### AI Policies

**Privacy First**
- Keys stored in encrypted preferences (`AiPreferences`)
- History kept temporary in memory (Stateless)
- User explicitly chooses provider and model

**Anti-Spoilers**
- Hard system prompt instruction: `Do NOT spoil anything beyond chapter X`
- Dynamic variable injection based on local reading history

---

## 🏗️ Architecture & Development

### Code Structure
- **Multi-module:** Maintain Mihon's structure (`app`, `core`, `data`, `domain`, `presentation-*`, `source-api`, etc.)
- **Compatibility:** 
  - Keep namespace `eu.kanade.tachiyomi` for extensions
  - Support `.tachibk` backups (add metadata, don't break format)
- **Clean code:** MVVM + Compose, clear repositories, separated layers

### Upstream & Fork Strategy

**Vendor Branches**
- Mirror branches: `vendor/upstream`, `vendor/j2k`, `vendor/sy`, `vendor/yokai`, `vendor/neko`, `vendor/komikku`
- Automated upstream sweep workflow
- Creates draft PRs for review
- Uses `git rerere` to remember conflict resolutions

**Integration Approach**
- **Selective cherry-pick:** Choose features to integrate, not blind merge
- **Feature branches:** Test changes before merging to main
- **Review process:** All upstream changes reviewed before integration

### Developer Experience (DX)

**CI/CD with GitHub Actions**
- Build Debug (`assembleStandardDebug`)
- Lint checks (`spotlessCheck`)
- Unit tests
- Upload APK artifacts
- Automated formatting (`spotless`)

**Code Quality**
- Clear diffs with file paths
- Ready-to-use code snippets
- Comprehensive inline documentation
- Type-safe DSL where possible

**Performance Standards**
- 60fps scrolling in library/feed
- Lazy loading for lists
- WorkManager for background tasks
- Efficient image caching

### Brand Identity

**Visual Design**
- **Logo:** "G" formed by a neon portal/halo
- **Color scheme:** Dark navy (#0B1220) + cyan (#00D1FF)
- **Aesthetic:** Futuristic but readable, strong dark mode
- **App feel:** More polished than average forks

**User Experience**
- Carefully crafted UX
- "Wow" features focused on reading experience and AI
- Not just "more settings"
- Accessibility-first design

---

## 📅 Development Phases

### ✅ Phase 1: Identity Foundation (COMPLETE)
- [x] Rebranding (app name, ID, icon, logo)
- [x] README and documentation
- [x] CI/CD workflows
- [x] Build verification

### 🚧 Phase 2: UI Modernization (IN PROGRESS)
- [x] Bottom navigation implementation (Home/Library/Updates/History/AI/More)
- [ ] Recents/Updates unified view
- [ ] Library UI improvements
- [ ] Tablet layout optimizations

### 🚧 Phase 3: Enhanced Reader (HIGH MATURITY)
- [x] Dual-page spread support
- [x] Reader mode improvements (webtoon/vertical/horizontal)
- [x] **Advanced Novel Reader:** HTML (Readability4J) & PDF (MuPDF) Support
- [x] **PDF Reflow:** Custom text rendering for PDFs
- [x] **Lazy OCR:** Background ML Kit extraction with priority queue
- [x] **Visual OCR:** Toggle between Image and Text modes
- [ ] TTS integration (Text-to-Speech)

### 📋 Phase 4: Content Control
- [ ] SFW toggle implementation
- [ ] Hidden categories
- [ ] PIN/biometric protection
- [ ] Library cleanup tools

### 📋 Phase 5: Multi-Source Intelligence
- [ ] Multi-source feed (Smart updates)

### 🚧 Phase 6: AI Integration (Client-Side)
- [x] **In-Reader Overlay:** Seamless chat without leaving the book
- [x] **Input Handling:** Keyboard isolation logic in ReaderActivity
- [x] Context/Anti-Spoiler Engine
- [x] Multi-Provider Support (Gemini, OpenAI, etc.)
- [x] Settings & Key Management
- [x] AI Tab Implementation
- [x] Local RAG (Vector Search)
- [ ] Visual Context (Multimodal input)
- [ ] Automatic Chapter Summaries

---

## 🤝 Contributing

We welcome contributions that align with Gexu's vision:

**Priority Areas**
- UI/UX improvements (especially tablet support)
- Performance optimizations
- Accessibility features
- Documentation

**Guidelines**
- Follow existing code style (use `spotlessApply`)
- Write clear commit messages
- Test on multiple device sizes
- Consider SFW implications of changes

**Not Accepting**
- Features that compromise SFW posture
- Performance-degrading changes
- Breaking changes to extension compatibility

---

## 📜 Technical Constraints

### Must Maintain
- ✅ Extension compatibility (namespace = `eu.kanade.tachiyomi`)
- ✅ Backup format compatibility (`.tachibk`)
- ✅ Min SDK 26 (Android 8.0+)
- ✅ Performance standards (60fps)

### Can Modify
- ✅ Application ID (`com.ramsesbr.gexu`)
- ✅ App name and branding
- ✅ Default settings and preferences
- ✅ UI/UX layouts and flows

### Should Not Change
- ❌ Core extension API contracts
- ❌ Backup file format structure
- ❌ Database migration chain
- ❌ Critical source-api interfaces

---

## 🎨 Design Philosophy

**Simplicity Over Complexity**
- Features should be intuitive, not buried in settings
- Smart defaults, fewer configuration screens
- Progressive disclosure of advanced features

**Performance Over Features**
- Every feature must justify its performance cost
- Smooth scrolling is non-negotiable
- Background tasks must be efficient

**Privacy Over Convenience**
- User data stays local by default
- AI features require explicit opt-in
- Transparent about data usage

**Compatibility Over Purity**
- Maintain extension ecosystem
- Support existing user workflows
- Gradual migration paths, not breaking changes

---

<div align="center">

**Gexu** — Where geek culture meets intelligent reading

Made with ❤️ for manga readers who want more

</div>
