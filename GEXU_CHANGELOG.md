# Gexu Changelog

All notable changes to Gexu will be documented in this file.

This is **separate** from the Mihon upstream [CHANGELOG.md](./CHANGELOG.md) which tracks upstream changes.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added (Gexu-Specific Features)
- **AI Chat Integration** — In-reader AI chat overlay with context awareness
- **Semantic Library Search (RAG)** — Vector-based search with BM25 hybrid re-ranking
- **Novel/PDF Reader** — Advanced text extraction with OCR (Google MLKit) and PDF reflow (MuPDF)
- **Hybrid Embeddings** — Cloud (Gemini 768-dim) + Local (MediaPipe USE 100-dim) for offline capability
- **Multi-Provider AI Support** — OpenAI, Gemini, Claude, Anthropic, OpenRouter, or custom endpoints
- **Anti-Spoiler Mode** — AI respects reading progress and won't reveal future chapters
- **Text Mode Toggle** — Switch between images and extracted/reflowed text on-the-fly
- **PDF Table of Contents** — Caching and navigation support for PDF files
- **Function Calling (Agent Tools)** — 26 AI tools for library queries, stats, and insights
- **Visual Selection (Circle-to-Search)** — Select image regions for AI analysis
- **Response Cache** — LRU cache with TTL for AI responses
- **Reading Time Stats** — Track and query time spent reading per manga

### Improved
- **VectorStore Cache Limit** — Increased from 2,000 to 10,000 embeddings (~30MB RAM)
- **TextChunker Size** — Increased from 1,500 to 2,500 characters for better single-chunk coverage
- **Tool Descriptions** — Enhanced descriptions for better AI tool selection

### Changed
- **Application ID** — `com.ramsesbr.gexu` (distinct from Mihon)
- **Branding** — Complete rebranding with new logo, name, and identity

### Technical
- **Clean Architecture** — AI/RAG system follows domain-data separation
- **Type-Safe Queries** — SQLDelight for compile-time SQL verification
- **Reactive Flows** — All database queries exposed as Kotlin Flows
- **Efficient Caching** — LRU caches for images, embeddings, and query results
- **Function Calling** — Full support for Gemini function calls with thought signature filtering

---

## Release Versioning (Future)

When Gexu has its first official release, versions will follow this format:

- **Major** (1.0.0) — Breaking changes or significant feature additions
- **Minor** (0.1.0) — New features, maintains backward compatibility
- **Patch** (0.0.1) — Bug fixes and minor improvements

---

## Upstream Sync

Gexu is based on **Mihon v0.19.3** (as of December 2025).

Upstream changes from Mihon are tracked in [CHANGELOG.md](./CHANGELOG.md).

