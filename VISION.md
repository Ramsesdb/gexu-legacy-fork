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
- **Dual-page spreads:** Proper pairing in landscape mode on tablets
- **Multiple modes:** Webtoon / horizontal / vertical, all polished
- **Performance:** Smooth 60fps scrolling with lazy loading

#### Novel Mode
- **Text viewer:** Customizable typography, size, line spacing, margins
- **Reading themes:** Dark mode, sepia, custom themes
- **Progress tracking:** By character/word offset
- **TTS integration:** Built-in text-to-speech with play/pause controls

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

#### Multi-Source Series Merge
- **Data model:** `MergedSeries { primaryMangaId, secondaryMangaIds[] }`
- **UI for merging:** User-friendly interface to link sources for same series
- **Combined chapters:** Unified chapter list with duplicate marking
- **Global progress:** Track progress as max chapter read across all sources

### 4. AI-Powered Reading Assistant

A lightweight backend (AstroIA/Gexu backend) built with .NET 8 + PostgreSQL + pgvector (or Qdrant).

#### Endpoints

**`/recs` — Recommendations**
- Based on reading history and preferences
- Avoids duplicates
- Respects SFW settings
- Considers reading progress

**`/search` — Semantic Search**
- Search by title, synopsis, notes, or tags
- Vector-based similarity matching
- Natural language queries

**`/chat` — Reading Companion**
- Q&A about series you're reading
- **Anti-spoiler protection:** Limited by your max chapter read
- Contextual answers based on your library

**`/recap` — Chapter Summaries**
- "Previously on..." summaries before starting a new chapter/arc
- Helps with long reading gaps
- Customizable summary length

**`/aliases/resolve` — Alias Resolution**
- Embedding-based + fuzzy matching
- Cross-reference IDs (MAL, AniList, etc.)
- Automatic duplicate detection

**`/cleanup/suggestions` — Library Cleanup**
- Detect duplicates and dead series
- Smart suggestions for library organization
- Batch operations support

#### AI Policies

**SFW First**
- No hentai recommendations in core features
- NSFW content requires explicit opt-in

**Anti-Spoilers**
- All AI features limited by user's `maxChapter`
- No future plot reveals or character deaths

**Privacy**
- Ask permission before sending data to AI
- Cache AI results to minimize token usage
- Optional offline mode (no AI sync)

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
- [ ] Bottom navigation implementation
- [ ] Recents/Updates unified view
- [ ] Library UI improvements
- [ ] Tablet layout optimizations

### 📋 Phase 3: Enhanced Reader
- [ ] Dual-page spread support
- [ ] Reader mode improvements (webtoon/vertical/horizontal)
- [ ] Novel mode implementation
- [ ] TTS integration

### 📋 Phase 4: Content Control
- [ ] SFW toggle implementation
- [ ] Hidden categories
- [ ] PIN/biometric protection
- [ ] Library cleanup tools

### 📋 Phase 5: Multi-Source Intelligence
- [ ] Multi-source feed
- [ ] Source merging UI
- [ ] Combined chapter lists
- [ ] Global progress tracking

### 📋 Phase 6: AI Integration
- [ ] Backend infrastructure (AstroIA)
- [ ] Recommendations endpoint
- [ ] Semantic search
- [ ] Reading companion chat
- [ ] Chapter recaps
- [ ] Alias resolution

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
