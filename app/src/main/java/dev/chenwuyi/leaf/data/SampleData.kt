package dev.chenwuyi.leaf.data

import java.time.LocalDateTime
import java.time.ZoneId

/** Demo content until M1 reads the user's Notion database. Mirrors the prototype's data. */
object SampleData {
    private fun at(month: Int, day: Int, hour: Int) =
        LocalDateTime.of(2026, month, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant()

    private fun s(id: Int, text: String, color: NotionColor, month: Int, day: Int, hour: Int) =
        Scribble("s$id", text, color, at(month, day, hour))

    val scribbles: List<Scribble> = listOf(
        s(1, "好的工具应该安静", NotionColor.Default, 10, 23, 8),
        s(2, "今天想清楚了：Leaf 不做 AI 代写", NotionColor.Red, 10, 22, 23),
        s(3, "Relay 这个名字像接力，东西从手机交给电脑", NotionColor.Pink, 10, 22, 14),
        s(4, "读书时的停顿也是阅读的一部分", NotionColor.Blue, 10, 21, 22),
        s(5, "平板竖着拿是书，横着拿是桌子", NotionColor.Default, 10, 21, 10),
        s(6, "写作是把散落的句子收拢成一束", NotionColor.Green, 10, 20, 21),
        s(7, "为什么划线比收藏更像阅读？收藏是囤，划线是读过", NotionColor.Blue, 10, 20, 9),
        s(8, "Notion 的高亮色其实很克制，只有九种", NotionColor.Gray, 10, 19, 16),
        s(9, "东京的便利店灯光比上海暖一点", NotionColor.Orange, 10, 18, 20),
        s(10, "麻辣烫在东京也开始流行了", NotionColor.Brown, 10, 18, 13),
        s(11, "慢不是低效，是让东西有机会沉下去", NotionColor.Green, 10, 17, 23),
        s(12, "睡前听 Endel，醒来记不住梦", NotionColor.Purple, 10, 16, 7),
        s(13, "Sheaf 的翻页能不能有一点纸的阻力", NotionColor.Default, 10, 15, 15),
        s(14, "涂鸦墙应该像冰箱门，不像收件箱", NotionColor.Yellow, 10, 14, 11),
        s(15, "早上的咖啡比晚上的酒更适合想事情", NotionColor.Brown, 10, 13, 8),
        s(16, "句子会自己找到彼此", NotionColor.Purple, 10, 12, 22),
        s(17, "Keep 的颜色热闹，Notion 的颜色安静", NotionColor.Yellow, 10, 12, 12),
        s(18, "把手机放远一点读书", NotionColor.Green, 10, 11, 21),
    )

    private var block = 0
    private fun p(text: String) = EssayBlock.Paragraph("b${++block}", text)
    private fun sync(scribble: Int) = EssayBlock.SyncedScribble("b${++block}", "s$scribble")

    val essays: List<Essay> = listOf(
        Essay(
            "e21", "为什么我还想做一个安静的阅读器",
            listOf(
                p("做 Sheaf 的这几个月，我删掉的功能比加上的多。未读数、连续天数、完成率，每一个都很容易做，也都会把人往“多读一点”推。"),
                sync(1),
                p("我想要的是另一种推力：让人愿意停下来。停顿不是阅读的失败，它是阅读的一部分。"),
            ),
            NotionColor.Default, Picture.Sample(SampleArt.Marble), pinned = true, editedAt = at(10, 22, 21),
        ),
        Essay(
            "e22", "24 岁的一些小事",
            listOf(
                p("我变得越来越喜欢用手写的东西记事，因为它不会提醒我。"),
                p("我对新的科技仍然感到兴奋，但并非持有积极态度。我觉得生活应该非常复杂，但看起来是困难而不是复杂。"),
            ),
            NotionColor.Purple, null, pinned = false, editedAt = at(10, 21, 23),
        ),
        Essay(
            "e23", "东京第三天",
            listOf(
                p("早上在住处附近的路口站了一会儿，橙色的施工围栏把一块空地圈起来，像一个还没写完的段落。"),
                sync(9),
                p("晚上吃了麻辣烫，排队的人比我想的多。"),
            ),
            NotionColor.Default, Picture.Sample(SampleArt.Street), pinned = false, editedAt = at(10, 18, 22),
        ),
        Essay(
            "e24", "关于慢",
            listOf(p("慢不是低效。很多东西需要时间沉下去，才能看清楚它原本的形状。"), p("慢慢读，慢慢写，慢慢决定要不要留下。")),
            NotionColor.Green, null, pinned = false, editedAt = at(10, 17, 23),
        ),
        Essay(
            "e25", "Relay 的名字",
            listOf(p("手机上看到一篇文章，分享出去，电脑浏览器里就出现了。中间这一段我想叫它 Relay，像接力棒，从一只手交到另一只手。")),
            NotionColor.Default, null, pinned = false, editedAt = at(10, 15, 18),
        ),
        Essay(
            "e26", "平板的第二种用法",
            listOf(p("竖着拿是书，横着拿是桌子。双页摊开的时候，右边那页应该留给笔。")),
            NotionColor.Blue, Picture.Sample(SampleArt.Dusk), pinned = false, editedAt = at(10, 14, 22),
        ),
    )
}
