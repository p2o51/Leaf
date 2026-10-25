package dev.chenwuyi.leaf.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.chenwuyi.leaf.MainActivity
import dev.chenwuyi.leaf.data.notion.NotionOAuthSession

/**
 * Where notion-oauth-relay sends the browser back to (leaf://notion-oauth). It shows nothing: it hands
 * the callback to settings and gets out of the way, so Leaf's task is what the browser returns to.
 */
class NotionOAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotionOAuthSession.shared.deliver(intent?.dataString)
        // Leaf was not open underneath (its task was cleared meanwhile): start it rather than land nowhere.
        if (isTaskRoot) startActivity(packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java))
        finish()
    }
}
