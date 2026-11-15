package app.orchard.tv.system

import app.orchard.tv.system.Backup.FolderSnapshot
import app.orchard.tv.system.Backup.LayoutSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Importing a backup onto the TV that made it is a replace; onto another TV it is a union. The
 * other TV may run apps the backup never knew about, and they sit inside folders here too.
 */
class BackupLayoutMergeTest {
    private fun theirs(order: List<String>, folders: List<FolderSnapshot>, hidden: Set<String> = emptySet()) =
        LayoutSnapshot(order, hidden, folders)

    private fun mine(order: List<String>, folders: List<FolderSnapshot>, hidden: Set<String> = emptySet()) =
        LayoutSnapshot(order, hidden, folders)

    @Test
    fun anAppOfMineInAFileWithTheSameNameJoinsThatFolder() {
        val merged = Backup.mergeLayout(
            mine(listOf("A", "folder:7"), listOf(FolderSnapshot("7", "系统应用", listOf("A", "C"))), emptySet()),
            theirs(listOf("A", "folder:1", "B"), listOf(FolderSnapshot("1", "系统应用", listOf("A"))), emptySet()),
        )
        assertEquals(listOf("A", "folder:1", "B"), merged.order)
        assertEquals(listOf(FolderSnapshot("1", "系统应用", listOf("A", "C"))), merged.folders)
    }

    @Test
    fun aFolderTheBackupNeverHadComesAlongWithAnIdOfItsOwn() {
        val merged = Backup.mergeLayout(
            mine(listOf("folder:2", "A"), listOf(FolderSnapshot("2", "游戏", listOf("G"))), emptySet()),
            theirs(listOf("A", "folder:1"), listOf(FolderSnapshot("1", "系统应用", listOf("A"))), emptySet()),
        )
        assertEquals(listOf("A", "folder:1", "folder:2"), merged.order)
        assertEquals(listOf(FolderSnapshot("1", "系统应用", listOf("A")), FolderSnapshot("2", "游戏", listOf("G"))), merged.folders)
    }

    @Test
    fun anAppOfMineLooseOnHomeGoesToTheEnd() {
        val merged = Backup.mergeLayout(
            mine(listOf("A", "X"), emptyList(), emptySet()),
            theirs(listOf("A", "B"), emptyList(), emptySet()),
        )
        // The backup keeps its own order; what only this TV has follows, in the order it had.
        assertEquals(listOf("A", "B", "X"), merged.order)
    }

    @Test
    fun anAppBothHaveKeepsThePlaceTheBackupGaveIt() {
        val merged = Backup.mergeLayout(
            mine(listOf("B", "A"), emptyList(), emptySet()),
            theirs(listOf("A", "B"), emptyList(), emptySet()),
        )
        assertEquals(listOf("A", "B"), merged.order)
    }

    @Test
    fun hiddenIsTheUnionSoWhatWasHiddenHereStaysHidden() {
        val merged = Backup.mergeLayout(
            mine(listOf("A", "X"), emptyList(), setOf("X")),
            theirs(listOf("A", "B"), emptyList(), setOf("B")),
        )
        assertEquals(setOf("B", "X"), merged.hidden)
    }

    @Test
    fun aBackupOnATVThatNeverArrangedAnythingIsTakenAsItIs() {
        val backup = theirs(listOf("A", "folder:1"), listOf(FolderSnapshot("1", "影视", listOf("B"))))
        assertEquals(backup, Backup.mergeLayout(null, backup))
    }

    @Test
    fun noAppIsWrittenDownTwice() {
        val merged = Backup.mergeLayout(
            mine(listOf("folder:3", "A", "X"), listOf(FolderSnapshot("3", "工具", listOf("A", "X"))), emptySet()),
            theirs(listOf("A", "B"), emptyList(), emptySet()),
        )
        assertEquals(merged.order.size, merged.order.toSet().size)
        assertEquals(listOf("A", "B", "folder:1"), merged.order)
        assertEquals(listOf(FolderSnapshot("1", "工具", listOf("X"))), merged.folders)
    }

    @Test
    fun theSourcesOfThisTVStayWhereTheyWere() {
        val merged = Backup.mergeLayout(
            mine(listOf("input:a", "input:b"), emptyList(), emptySet()),
            theirs(listOf("input:a"), emptyList(), emptySet()),
        )
        assertTrue(merged.order.containsAll(listOf("input:a", "input:b")))
        assertEquals(2, merged.order.size)
    }
}
