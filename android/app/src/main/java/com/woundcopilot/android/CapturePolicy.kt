package com.woundcopilot.android

internal fun protectScreen(debug:Boolean,demo:Boolean,locked:Boolean,screen:String,clinicalContext:Boolean):Boolean {
    if(debug || demo)return false
    return locked || clinicalContext || screen !in setOf("welcome","login","signup","reconnect","account","developer_diagnostics")
}
internal fun AppState.protectCapture():Boolean {
    val messages=error!=null || notice!=null
    val clinical=when(screen){
        "welcome","login","signup","reconnect","account","developer_diagnostics"->messages
        "home"->messages || encounters.isNotEmpty() || patients.isNotEmpty()
        else->case!=null || editor.isNotBlank() || messages
    }
    val route=if(screen=="home"&&!clinical)"welcome"else screen
    return protectScreen(BuildConfig.DEBUG || BuildConfig.ALLOW_SCREEN_CAPTURE,demo,locked,route,clinical)
}
