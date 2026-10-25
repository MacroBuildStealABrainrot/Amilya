@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.content

object ContextCompat {
    @JvmStatic fun checkSelfPermission(context: android.content.Context, permission: String): Int = TODO()
    @JvmStatic fun startForegroundService(context: android.content.Context, intent: android.content.Intent) {}
    const val RECEIVER_NOT_EXPORTED = 4
    @JvmStatic fun registerReceiver(context: android.content.Context, receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter, flags: Int): android.content.Intent? = null
}
open class FileProvider : android.content.ContentProvider() {
    companion object { @JvmStatic fun getUriForFile(context: android.content.Context, authority: String, file: java.io.File): android.net.Uri = TODO() }
    override fun onCreate() = true
    override fun query(uri: android.net.Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): android.database.Cursor? = null
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<String>?) = 0
}
