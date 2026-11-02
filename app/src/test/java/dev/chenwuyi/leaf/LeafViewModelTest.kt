package dev.chenwuyi.leaf

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.InMemoryLeafRepository
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallSort
import dev.chenwuyi.leaf.ui.LeafViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeafViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

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
        val copied = vm.essays.value.first { it.id == "e25" }.blocks.filterIsInstance<EssayBlock.Paragraph>().map { it.text }.filter { it.isNotEmpty() }
        assertEquals(listOf(SampleData.essays.first { it.id == "e25" }.previewText, "读书时的停顿也是阅读的一部分", "Relay 这个名字像接力，东西从手机交给电脑"), copied)
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
        var removed = emptyList<Scribble>()
        vm.delete(setOf("s1", "s2")) { removed = it }
        assertTrue(vm.scribbles.value.none { it.id == "s1" })
        vm.restore(removed)
        assertTrue(vm.scribbles.value.any { it.id == "s1" } && vm.scribbles.value.any { it.id == "s2" })
    }

    @Test
    fun should_ignore_blank_text_when_adding_a_scribble_without_a_photo() {
        val vm = vm()
        var added: Scribble? = null
        vm.addScribble("   ", NotionColor.Default) { added = it }
        assertNull(added)
        vm.addScribble(" 新的一句 ", NotionColor.Green) { added = it }
        assertEquals("新的一句", added?.text)
    }

    @Test
    fun should_keep_sort_and_drop_selection_when_reopening_the_wall_on_another_chip() {
        val vm = vm()
        vm.openWall("s1", emptySet())
        vm.setSort(WallSort.Color)
        vm.openWall("s2", setOf("s3"))
        assertEquals(WallSort.Color, vm.wall.value?.sort)
        assertEquals("s2", vm.wall.value?.focusId)
    }

    @Test
    fun should_show_only_matching_scribbles_and_essays_when_a_tag_is_picked() {
        val vm = vm()
        vm.setFilter(HomeFilter.ByTag("东京"))
        assertEquals(setOf("s9", "s10"), vm.visibleScribbles.value.map { it.id }.toSet())
        assertEquals(listOf("e23"), vm.visibleEssays.value.map { it.id })
        assertTrue("东京" in vm.tags.value)
        vm.setFilter(HomeFilter.All)
        assertEquals(SampleData.scribbles.size, vm.visibleScribbles.value.size)
    }

    @Test
    fun should_select_only_what_the_wall_shows_when_selecting_all_under_a_filter() {
        val vm = vm()
        vm.setFilter(HomeFilter.ByColor(NotionColor.Green))
        vm.openWall(null, emptySet())
        vm.startSelecting()
        vm.selectAll()
        assertEquals(setOf("s6", "s11", "s18"), vm.wall.value?.selected)
    }

    @Test
    fun should_drop_scribbles_from_the_selection_when_a_recolour_moves_them_out_of_the_filter() {
        val vm = vm()
        vm.setFilter(HomeFilter.ByColor(NotionColor.Green))
        vm.openWall(null, emptySet())
        vm.startSelecting("s6")
        vm.toggleSelected("s11")
        vm.recolor(setOf("s6"), NotionColor.Red)
        assertEquals(setOf("s11"), vm.wall.value?.selected)
    }

    // A photo's delete waits out the same undo window as an essay's: nothing reaches Room (and so Notion)
    // until the snackbar has gone.

    private fun photoEssay(vararg ids: String) = Essay(
        "p",
        "照片",
        ids.map { if (it.startsWith("i")) EssayBlock.Photo(it, Picture.Local("content://$it")) else EssayBlock.Paragraph(it, "段落 $it") },
        NotionColor.Default,
        null,
        false,
        Instant.EPOCH,
    )

    /** A view model on a clock the test moves by hand. */
    private fun timed(repository: InMemoryLeafRepository): Pair<LeafViewModel, TestCoroutineScheduler> {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        return LeafViewModel(repository) to scheduler
    }

    private fun InMemoryLeafRepository.blockIds() = essays.value.single { it.id == "p" }.blocks.map { it.id }
    private fun LeafViewModel.blockIds() = essays.value.single { it.id == "p" }.blocks.map { it.id }

    @Test
    fun should_hide_the_photo_at_once_but_keep_it_stored_until_the_undo_window_ends_when_a_photo_is_deleted() {
        val repository = InMemoryLeafRepository(essays = listOf(photoEssay("a", "i1", "b")))
        val (vm, clock) = timed(repository)
        vm.removePhoto("p", "i1")
        assertEquals(listOf("a", "b"), vm.blockIds())
        assertEquals(listOf("a", "i1", "b"), repository.blockIds())
        clock.advanceTimeBy(LeafViewModel.UndoWindowMillis - 1)
        assertEquals(listOf("a", "i1", "b"), repository.blockIds())
        clock.advanceTimeBy(2)
        assertEquals(listOf("a", "b"), repository.blockIds())
        assertEquals(listOf("a", "b"), vm.blockIds())
    }

    @Test
    fun should_put_the_photo_back_in_its_place_and_never_remove_it_when_undoing_a_photo_delete() {
        val repository = InMemoryLeafRepository(essays = listOf(photoEssay("a", "i1", "b")))
        val (vm, clock) = timed(repository)
        vm.removePhoto("p", "i1")
        vm.undoRemovePhoto("p", "i1")
        assertEquals(listOf("a", "i1", "b"), vm.blockIds())
        clock.advanceTimeBy(LeafViewModel.UndoWindowMillis * 2)
        assertEquals(listOf("a", "i1", "b"), repository.blockIds())
    }

    @Test
    fun should_keep_the_hidden_photo_stored_when_the_essay_is_edited_during_the_undo_window() {
        val repository = InMemoryLeafRepository(essays = listOf(photoEssay("a", "i1", "b")))
        val (vm, _) = timed(repository)
        vm.removePhoto("p", "i1")
        // The editor writes back its draft, which no longer has the photo.
        vm.updateEssay(vm.essays.value.single { it.id == "p" }.copy(title = "改过"))
        assertEquals(listOf("a", "i1", "b"), repository.blockIds())
        assertEquals("改过", repository.essays.value.single().title)
        vm.undoRemovePhoto("p", "i1")
        assertEquals(listOf("a", "i1", "b"), vm.blockIds())
    }

    @Test
    fun should_count_a_move_against_the_shown_blocks_when_a_photo_is_hidden() {
        val repository = InMemoryLeafRepository(essays = listOf(photoEssay("a", "i1", "b", "c")))
        val (vm, _) = timed(repository)
        vm.removePhoto("p", "i1")
        // The editor shows a, b, c and drops c in front of a.
        vm.moveBlock("p", "c", 0)
        assertEquals(listOf("c", "a", "i1", "b"), repository.blockIds())
        assertEquals(listOf("c", "a", "b"), vm.blockIds())
    }
}
