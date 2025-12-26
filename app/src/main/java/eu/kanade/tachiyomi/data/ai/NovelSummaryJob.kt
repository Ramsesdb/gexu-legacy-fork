package eu.kanade.tachiyomi.data.ai

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.MuPdfUtil
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.ai.interactor.GenerateNovelSummary
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Background worker that generates progressive AI summaries for novels/PDFs.
 * Triggered when user reads 30+ pages beyond the last summary checkpoint.
 */
class NovelSummaryJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val generateNovelSummary: GenerateNovelSummary = Injekt.get()
    private val mangaRepository: MangaRepository = Injekt.get()

    override suspend fun doWork(): Result {
        val mangaId = inputData.getLong(KEY_MANGA_ID, -1)
        val fromPage = inputData.getInt(KEY_FROM_PAGE, 0)
        val toPage = inputData.getInt(KEY_TO_PAGE, 0)
        val pdfPath = inputData.getString(KEY_PDF_PATH)
        val chapterNumber = inputData.getDouble(KEY_CHAPTER_NUMBER, 0.0)

        if (mangaId == -1L || pdfPath.isNullOrBlank()) {
            logcat(LogPriority.WARN) { "NovelSummaryJob: Invalid input data" }
            return Result.failure()
        }

        logcat(LogPriority.INFO) {
            "NovelSummaryJob: Starting summary generation for manga $mangaId, pages $fromPage-$toPage"
        }

        return try {
            // 1. Extract text from PDF pages
            val pdfFile = File(pdfPath)
            if (!pdfFile.exists()) {
                logcat(LogPriority.WARN) { "NovelSummaryJob: PDF file not found: $pdfPath" }
                return Result.failure()
            }

            val extractedText = extractTextFromPages(pdfFile, fromPage, toPage)
            if (extractedText.isBlank()) {
                logcat(LogPriority.WARN) { "NovelSummaryJob: No text extracted from pages" }
                return Result.failure()
            }

            logcat(LogPriority.INFO) {
                "NovelSummaryJob: Extracted ${extractedText.length} chars from pages $fromPage-$toPage"
            }

            // 2. Generate summary via AI (passes chapter number to prevent overwriting newer summaries)
            val summary = generateNovelSummary.await(mangaId, extractedText, toPage, chapterNumber)

            if (summary != null) {
                logcat(LogPriority.INFO) {
                    "NovelSummaryJob: Summary generated successfully (${summary.length} chars)"
                }
                Result.success()
            } else {
                logcat(LogPriority.WARN) { "NovelSummaryJob: Summary generation returned null" }
                Result.retry()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "NovelSummaryJob: Failed to generate summary" }
            Result.failure()
        }
    }

    private suspend fun extractTextFromPages(
        pdfFile: File,
        fromPage: Int,
        toPage: Int,
    ): String = withContext(Dispatchers.IO) {
        val document = try {
            MuPdfUtil.openDocument(pdfFile.absolutePath)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "NovelSummaryJob: Failed to open PDF" }
            null
        }

        if (document == null) {
            logcat(LogPriority.ERROR) { "NovelSummaryJob: Document is null, cannot extract text" }
            return@withContext ""
        }

        try {
            val textBuilder = StringBuilder()
            val pageCount = MuPdfUtil.getPageCount(document)
            val safeFrom = fromPage.coerceIn(0, pageCount - 1)
            val safeTo = toPage.coerceIn(0, pageCount - 1)

            for (i in safeFrom..safeTo) {
                val pageText = MuPdfUtil.extractPageText(document, i)
                if (pageText.isNotBlank()) {
                    textBuilder.appendLine("--- Page ${i + 1} ---")
                    textBuilder.appendLine(pageText)
                }
            }
            textBuilder.toString()
        } finally {
            MuPdfUtil.closeDocument(document)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = android.app.Notification.Builder(
            context,
            Notifications.CHANNEL_COMMON,
        ).apply {
            setContentTitle("Generating story summary...")
            setSmallIcon(android.R.drawable.ic_menu_edit)
            setOngoing(true)
        }.build()

        return ForegroundInfo(
            Notifications.ID_AI_INDEXING_PROGRESS + 1,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val TAG = "NovelSummary"
        private const val KEY_MANGA_ID = "manga_id"
        private const val KEY_FROM_PAGE = "from_page"
        private const val KEY_TO_PAGE = "to_page"
        private const val KEY_PDF_PATH = "pdf_path"
        private const val KEY_CHAPTER_NUMBER = "chapter_number"

        /**
         * Enqueue a summary generation job.
         *
         * @param context Android context
         * @param mangaId The manga/novel ID
         * @param fromPage Start page for text extraction
         * @param toPage End page for text extraction
         * @param pdfPath Absolute path to the PDF file
         */
        fun enqueue(
            context: Context,
            mangaId: Long,
            fromPage: Int,
            toPage: Int,
            pdfPath: String,
            chapterNumber: Double = 0.0,
        ) {
            val inputData = Data.Builder()
                .putLong(KEY_MANGA_ID, mangaId)
                .putInt(KEY_FROM_PAGE, fromPage)
                .putInt(KEY_TO_PAGE, toPage)
                .putString(KEY_PDF_PATH, pdfPath)
                .putDouble(KEY_CHAPTER_NUMBER, chapterNumber)
                .build()

            val request = OneTimeWorkRequestBuilder<NovelSummaryJob>()
                .addTag(TAG)
                .addTag("manga_$mangaId")
                .setInputData(inputData)
                .build()

            // Use REPLACE policy so only one summary job per manga at a time
            context.workManager.enqueueUniqueWork(
                "${TAG}_$mangaId",
                ExistingWorkPolicy.REPLACE,
                request,
            )

            logcat(LogPriority.INFO) {
                "NovelSummaryJob: Enqueued for manga $mangaId, pages $fromPage-$toPage"
            }
        }

        fun cancel(context: Context, mangaId: Long) {
            context.workManager.cancelUniqueWork("${TAG}_$mangaId")
        }
    }
}
