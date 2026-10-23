package dev.chenwuyi.leaf

import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.InMemoryLeafRepository
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.ui.LeafViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeafViewModelTest {
    private fun vm() = LeafViewModel(InMemoryLeafRepository(SampleData.scribbles, SampleData.essays))

    @Test
    fun should_close_the_wall_and_open_the_essay_when_copying_to_an_existing_essay() {
        val vm = vm()
        vm.openWall("s1", setOf("s2"))
        vm.startSelecting("s3")
        vm.toggleSelected("s4")
        vm.copyToEssay(vm.wall.value!!.selected, "e25")
        assertNull(vm.wall.value)
        assertEquals("e25", vm.openEssayId.value)
        val synced = vm.essays.value.first { it.id == "e25" }.blocks.filterIsInstance<EssayBlock.SyncedScribble>().map { it.scribbleId }
        assertEquals(listOf("s4", "s3"), synced)
    }

    @Test
    fun should_create_an_essay_when_copying_with_no_target() {
        val vm = vm()
        val before = vm.essays.value.size
        vm.openWall(null, emptySet())
        vm.copyToEssay(setOf("s1"), null)
        assertEquals(before + 1, vm.essays.value.size)
        assertEquals(vm.essays.value.last().id, vm.openEssayId.value)
    }

    @Test
    fun should_restore_deleted_scribbles_when_undoing() {
        val vm = vm()
        val removed = vm.delete(setOf("s1", "s2"))
        assertTrue(vm.scribbles.value.none { it.id == "s1" })
        vm.restore(removed)
        assertTrue(vm.scribbles.value.any { it.id == "s1" } && vm.scribbles.value.any { it.id == "s2" })
    }

    @Test
    fun should_ignore_blank_text_when_adding_a_scribble_without_a_photo() {
        val vm = vm()
        assertNull(vm.addScribble("   ", NotionColor.Default))
        assertEquals("新的一句", vm.addScribble(" 新的一句 ", NotionColor.Green)?.text)
    }

    @Test
    fun should_keep_sort_and_drop_selection_when_reopening_the_wall_on_another_chip() {
        val vm = vm()
        vm.openWall("s1", emptySet())
        vm.setSort(dev.chenwuyi.leaf.data.WallSort.Color)
        vm.openWall("s2", setOf("s3"))
        assertEquals(dev.chenwuyi.leaf.data.WallSort.Color, vm.wall.value?.sort)
        assertEquals("s2", vm.wall.value?.focusId)
    }
}
