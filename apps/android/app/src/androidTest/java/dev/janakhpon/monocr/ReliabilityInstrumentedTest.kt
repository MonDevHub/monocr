package dev.janakhpon.monocr

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.janakhpon.monocr.data.HistoryDatabase
import dev.janakhpon.monocr.data.HistoryRecord
import dev.janakhpon.monocr.engine.MonOcrEngine
import dev.janakhpon.monocr.util.orientBitmap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Bitmap/SQLite/ORT boundaries. Requires a device; JVM helper passes do not replace this suite. */
class ReliabilityInstrumentedTest {
    @Test fun allExifOrientationsTransformRealBitmapPixels() {
        val expected = listOf("abcdef", "cbafed", "fedcba", "defabc", "adbecf", "daebfc", "fcebda", "cfbead")
        for (orientation in 1..8) {
            val pixels = IntArray(6) { 0xff000000.toInt() or (it + 1) }
            val source = Bitmap.createBitmap(pixels, 3, 2, Bitmap.Config.ARGB_8888)
            val upright = orientBitmap(source, orientation)
            try {
                assertEquals(if (orientation >= 5) 2 else 3, upright.width)
                val output = IntArray(6)
                upright.getPixels(output, 0, upright.width, 0, 0, upright.width, upright.height)
                assertEquals(expected[orientation - 1], output.map { 'a' + ((it and 255) - 1) }.joinToString(""))
            } finally { upright.recycle() }
        }
    }

    @Test fun historyUpgradePreservesOldRowsAndNewRawWarnings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-upgrade-${System.nanoTime()}.db"
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE history_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, fileName TEXT NOT NULL, fileType TEXT NOT NULL, text TEXT NOT NULL, processingTime INTEGER NOT NULL, category TEXT NOT NULL, fileUri TEXT, syncId TEXT NOT NULL, isSynced INTEGER NOT NULL, syncAttempts INTEGER NOT NULL, syncError TEXT)")
            db.execSQL("INSERT INTO history_records VALUES (1,0,'old','image/jpeg','retained',1,'ocr-scan',NULL,'old-id',0,0,NULL)")
            db.version = 3
        }
        val room = Room.databaseBuilder(context, HistoryDatabase::class.java, name)
            .addMigrations(HistoryDatabase.MIGRATION_3_4).build()
        try {
            val old = room.historyDao().getRecordsByCategory("ocr-scan").first().single()
            assertEquals("retained", old.text)
            assertNull(old.rawText)
            assertNull(old.warningSummary)
            room.historyDao().insert(HistoryRecord(fileName = "new", fileType = "application/pdf",
                text = "\u1026", processingTime = 1, rawText = "\u1025\u102e", warningSummary = "Page 2 failed"))
            val saved = room.historyDao().getRecordsByCategory("ocr-scan").first().first { it.fileName == "new" }
            assertEquals("\u1025\u102e", saved.rawText)
            assertEquals("Page 2 failed", saved.warningSummary)
        } finally { room.close(); context.deleteDatabase(name) }
    }

    @Test fun bundledRuntimeLoadsAndDecodesWhiteInput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = MonOcrEngine(context)
        try {
            engine.initialize()
            assertEquals("", engine.runInference(FloatArray(160 * 1024) { 1f }))
        } finally { engine.dispose() }
    }
}
