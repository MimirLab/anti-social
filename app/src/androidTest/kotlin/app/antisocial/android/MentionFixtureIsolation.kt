/* AntiSocial, GPL-3.0-or-later. Deterministic network fixtures only. */
package app.antisocial.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Existing controlled fixtures must not launch unrelated public profile reads.
 * Dedicated name tests explicitly enable lookups or inject their own resolver.
 * Opt in with AndroidJUnitRunner's -e listener argument; no production branch. */
class MentionFixtureIsolation: RunListener() {
    private var existed=false
    private var previous=true
    override fun testStarted(description: Description) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("antisocial",Context.MODE_PRIVATE)
        existed=prefs.contains("mentionLookups");previous=prefs.getBoolean("mentionLookups",true)
        prefs.edit().putBoolean("mentionLookups",false).commit()
        (context.applicationContext as AntiSocialApplication).repo.mentionNames.changed()
    }
    override fun testFinished(description: Description) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val editor=context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit()
        if(existed)editor.putBoolean("mentionLookups",previous)else editor.remove("mentionLookups")
        editor.commit();(context.applicationContext as AntiSocialApplication).repo.mentionNames.changed()
    }
}
