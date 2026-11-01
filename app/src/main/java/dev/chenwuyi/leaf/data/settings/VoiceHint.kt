package dev.chenwuyi.leaf.data.settings

import android.content.Context

/**
 * The one hint Leaf gives about holding +. Someone who keeps opening the menu to pick 说一句 has not found
 * the long press, so on their [PicksBeforeHint]th pick the voice sheet says it, once. A hold on + means
 * they know already, and the hint never comes. Kept in its own prefs file so nothing else resets it.
 */
class VoiceHint(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("leaf_hints", Context.MODE_PRIVATE)

    /** Counts one pick of 说一句 from the menu; true only for the pick the hint shows on, which uses it up. */
    fun pickedFromMenu(): Boolean {
        if (prefs.getBoolean(KeyDone, false)) return false
        val picks = prefs.getInt(KeyPicks, 0) + 1
        val show = picks >= PicksBeforeHint
        prefs.edit().putInt(KeyPicks, picks).putBoolean(KeyDone, show).apply()
        return show
    }

    fun heldToTalk() {
        if (!prefs.getBoolean(KeyDone, false)) prefs.edit().putBoolean(KeyDone, true).apply()
    }

    companion object {
        const val PicksBeforeHint = 3
        // In words, not "+": read aloud, or at a glance, the sign read as 和.
        const val Text = "下次可以长按加号直接说"
        private const val KeyPicks = "voice_menu_picks"
        private const val KeyDone = "voice_hint_done"
    }
}
