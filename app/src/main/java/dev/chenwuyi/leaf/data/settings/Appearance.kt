package dev.chenwuyi.leaf.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The reading text's face: cards, the essay title and body. Tool text stays Google Sans Flex either way. */
enum class ReadingFont(val label: String) { Serif("衬线"), Sans("无衬线") }

enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

/** 设置 › 外观. */
data class Appearance(val font: ReadingFont = ReadingFont.Serif, val theme: ThemeMode = ThemeMode.System)

interface AppearanceStore {
    val appearance: StateFlow<Appearance>
    fun set(appearance: Appearance)
}

class PrefsAppearanceStore(context: Context) : AppearanceStore {
    private val prefs = context.applicationContext.getSharedPreferences("leaf_appearance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        Appearance(
            font = ReadingFont.entries.firstOrNull { it.name == prefs.getString("font", null) } ?: ReadingFont.Serif,
            theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.System,
        ),
    )
    override val appearance: StateFlow<Appearance> = state.asStateFlow()

    override fun set(appearance: Appearance) {
        prefs.edit().putString("font", appearance.font.name).putString("theme", appearance.theme.name).apply()
        state.value = appearance
    }
}

class MemoryAppearanceStore(initial: Appearance = Appearance()) : AppearanceStore {
    private val state = MutableStateFlow(initial)
    override val appearance: StateFlow<Appearance> = state.asStateFlow()
    override fun set(appearance: Appearance) { state.value = appearance }
}
