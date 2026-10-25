// Stand-in declarations of the few AndroidX APIs used by the non-screen Kotlin files (state holders, services).
// Signatures follow the public AndroidX API. Used only to type-check; never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.runtime

import kotlin.reflect.KProperty

interface State<out T> { val value: T }
interface MutableState<T> : State<T> { override var value: T }
interface MutableIntState : MutableState<Int> { var intValue: Int }
interface MutableLongState : MutableState<Long> { var longValue: Long }
fun <T> mutableStateOf(value: T): MutableState<T> = TODO()
fun mutableIntStateOf(value: Int): MutableIntState = TODO()
fun mutableLongStateOf(value: Long): MutableLongState = TODO()
operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value
operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) { this.value = value }
operator fun MutableIntState.getValue(thisObj: Any?, property: KProperty<*>): Int = intValue
operator fun MutableIntState.setValue(thisObj: Any?, property: KProperty<*>, value: Int) { intValue = value }
operator fun MutableLongState.getValue(thisObj: Any?, property: KProperty<*>): Long = longValue
operator fun MutableLongState.setValue(thisObj: Any?, property: KProperty<*>, value: Long) { longValue = value }
fun <T> snapshotFlow(block: () -> T): kotlinx.coroutines.flow.Flow<T> = TODO()
annotation class Composable
