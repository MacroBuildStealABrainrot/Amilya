package com.woundcopilot.android

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.*
import android.os.Build
import android.hardware.biometrics.BiometricManager

class PinLock(private val vault: Vault) {
    val configured get()=vault.read("app-pin")!=null
    private fun derive(pin:String,salt:ByteArray)=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(),salt,120000,256)).encoded
    @Synchronized fun set(pin:String){
        require(Regex("[0-9]{4}").matches(pin))
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
        vault.write("app-pin",android.util.Base64.encodeToString(salt+derive(pin,salt),android.util.Base64.NO_WRAP))
        vault.remove("pin-failures");vault.remove("pin-wait-until")
    }
    fun remainingSeconds()=((vault.read("pin-wait-until")?.toLongOrNull()?:0L)-System.currentTimeMillis()).coerceAtLeast(0)/1000
    @Synchronized fun verify(pin:String):Boolean {
        if(remainingSeconds()>0)return false
        val bytes=runCatching{android.util.Base64.decode(vault.read("app-pin"),android.util.Base64.NO_WRAP)}.getOrNull()?:return false
        val valid=bytes.size==48 && MessageDigest.isEqual(bytes.copyOfRange(16,48),derive(pin,bytes.copyOfRange(0,16)))
        if(valid){vault.remove("pin-failures");vault.remove("pin-wait-until")}else{
            val failures=(vault.read("pin-failures")?.toIntOrNull()?:0)+1
            vault.write("pin-failures",failures.toString())
            if(failures>=5)vault.write("pin-wait-until",(System.currentTimeMillis()+60_000L*(failures-4).coerceAtMost(30)).toString())
        }
        return valid
    }
    @Synchronized fun clear(){listOf("app-pin","pin-failures","pin-wait-until").forEach(vault::remove)}
}

@Composable fun AppLockScreen(s:AppState) {
    val context=LocalContext.current
    val pinLock=remember{s.pinLock}
    var pin by remember{mutableStateOf("")}
    var first by remember{mutableStateOf("")}
    var message by remember{mutableStateOf("")}
    var checking by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val owner=s.user?.str("id")
    val signal=remember(owner){CancellationSignal()}
    fun biometrics(){
        try{
            val builder=BiometricPrompt.Builder(context).setTitle("Unlock WoundCoPilot").setNegativeButton("Use PIN",context.mainExecutor){_,_->}
            if(Build.VERSION.SDK_INT>=29)builder.setConfirmationRequired(false)
            builder.build().authenticate(signal,context.mainExecutor,object:BiometricPrompt.AuthenticationCallback(){
                override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){if(!signal.isCanceled && s.user?.str("id")==owner)s.unlock()}
                override fun onAuthenticationError(code:Int,error:CharSequence){if(!signal.isCanceled)message="Use your PIN to unlock."}
            })
        }catch(_:Exception){message="Biometrics are unavailable. Use your PIN."}
    }
    DisposableEffect(owner){onDispose{signal.cancel()}}
    LaunchedEffect(owner){
        if(pinLock.configured && s.vault.read("biometric-auto")!="false" && Build.VERSION.SDK_INT>=29 &&
            context.getSystemService(BiometricManager::class.java).canAuthenticate()==BiometricManager.BIOMETRIC_SUCCESS)biometrics()
    }
    LaunchedEffect(pin){
        if(pinLock.configured && pin.length==4 && !checking){
            checking=true
            try{
                val valid=withContext(Dispatchers.Default){pinLock.verify(pin)}
                if(valid && s.user?.str("id")==owner){signal.cancel();s.unlock()}
                else message=if(pinLock.remainingSeconds()>0)"Too many attempts. Try again in ${pinLock.remainingSeconds()} seconds."else "PIN not recognized."
                pin=""
            }finally{checking=false}
        }
    }
    Page {
        Spacer(Modifier.height(48.dp))
        Text(if(pinLock.configured) "Unlock WoundCoPilot" else "Set your 4-digit PIN",style=MaterialTheme.typography.headlineMedium)
        Text(if(first.isNotEmpty())"Enter the PIN again." else if(pinLock.configured)"Your account is still signed in." else "Use this PIN when you return to the app.")
        OutlinedTextField(pin,{pin=it.mapNotNull{c->c.digitToIntOrNull()?.toString()}.joinToString("").take(4)},enabled=!checking,label={Text("PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),singleLine=true,modifier=Modifier.fillMaxWidth())
        if(checking)Text("Unlocking…")
        if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
        if(!pinLock.configured)Button(onClick={
            if(first.isEmpty()){first=pin;message="";pin=""}
            else if(first==pin){checking=true;val chosen=pin;scope.launch{try{withContext(Dispatchers.Default){pinLock.set(chosen)};if(s.user?.str("id")==owner)s.unlock()}finally{checking=false;pin=""}}}
            else{first="";pin="";message="The PINs did not match. Try again."}
        },enabled=pin.length==4 && s.user!=null && !checking,modifier=Modifier.fillMaxWidth()){Text(if(first.isEmpty())"Continue"else "Set PIN")}
        if(pinLock.configured)TextButton(onClick={biometrics()},enabled=!checking){Text("Use device biometrics")}
        TextButton(onClick={s.logout()}){Text("Sign out")}
    }
}
