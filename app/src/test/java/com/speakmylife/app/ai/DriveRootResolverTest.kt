package com.speakmylife.app.ai

import com.speakmylife.app.ai.DriveRootResolver.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drive 曲庫根資料夾改名搬遷（VoiceWeaver → Speak-My-Life）的決策規則。
 * 重點是最後一條：改名失敗時必須沿用舊資料夾，絕不能另建空的新資料夾讓曲庫「消失」。
 */
class DriveRootResolverTest {

    private class Fake(
        val newId: String? = null,
        val legacyId: String? = null,
        val renameOk: Boolean = true,
        val createdId: String? = "created",
    ) {
        val renamed = mutableListOf<String>()
        var created = false
        var legacyLooked = false

        fun run(create: Boolean = true) = DriveRootResolver.resolve(
            create = create,
            findNew = { newId },
            findLegacy = { legacyLooked = true; legacyId },
            rename = { renamed += it; renameOk },
            createNew = { created = true; createdId },
        )
    }

    @Test fun onlyNew_usesNew_andNeverTouchesLegacy() {
        val f = Fake(newId = "new", legacyId = "old")
        val r = f.run()
        assertEquals("new", r.id)
        assertEquals(Action.USE_NEW, r.action)
        assertFalse("有新的就不該去找舊的", f.legacyLooked)
        assertEquals(emptyList<String>(), f.renamed)
        assertFalse(f.created)
    }

    @Test fun onlyLegacy_isRenamedInPlace() {
        val f = Fake(legacyId = "old", renameOk = true)
        val r = f.run()
        assertEquals("old", r.id)                       // 同一個資料夾，id 不變
        assertEquals(Action.RENAMED_LEGACY, r.action)
        assertEquals(listOf("old"), f.renamed)
        assertFalse("改名搬遷不能另建新資料夾", f.created)
    }

    @Test fun renameFails_fallsBackToLegacy() {
        val f = Fake(legacyId = "old", renameOk = false)
        val r = f.run()
        assertEquals("old", r.id)
        assertEquals(Action.USE_LEGACY, r.action)
        assertFalse("改名失敗也不能另建空資料夾", f.created)
    }

    @Test fun renameThrows_fallsBackToLegacy() {
        val r = DriveRootResolver.resolve(
            create = true,
            findNew = { null }, findLegacy = { "old" },
            rename = { error("HTTP 403") }, createNew = { "created" },
        )
        assertEquals(DriveRootResolver.Result("old", Action.USE_LEGACY), r)
    }

    @Test fun neither_createsOnlyWhenAllowed() {
        val f = Fake()
        assertEquals(DriveRootResolver.Result("created", Action.CREATED), f.run(create = true))
        val g = Fake()
        val r = g.run(create = false)
        assertNull(r.id)
        assertEquals(Action.NONE, r.action)
        assertFalse(g.created)
    }
}
