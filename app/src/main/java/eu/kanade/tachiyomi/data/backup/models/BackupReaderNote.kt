package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Backup model for reader notes.
 * Uses chapter URL instead of ID for cross-installation compatibility.
 */
@Serializable
data class BackupReaderNote(
    @ProtoNumber(1) var chapterUrl: String,
    @ProtoNumber(2) var pageNumber: Int = 0,
    @ProtoNumber(3) var noteText: String,
    @ProtoNumber(4) var createdAt: Long,
    @ProtoNumber(5) var tags: String? = null,
)
