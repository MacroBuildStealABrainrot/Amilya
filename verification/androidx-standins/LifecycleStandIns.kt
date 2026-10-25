@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.lifecycle

abstract class ViewModel { protected open fun onCleared() {} }
open class AndroidViewModel(private val application: android.app.Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST") fun <T : android.app.Application> getApplication(): T = application as T
}
val ViewModel.viewModelScope: kotlinx.coroutines.CoroutineScope get() = TODO()
