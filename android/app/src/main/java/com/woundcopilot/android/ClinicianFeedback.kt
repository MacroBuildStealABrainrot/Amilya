package com.woundcopilot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.IOException

internal fun canRetryRequest(error:Exception)=
    error !is javax.net.ssl.SSLException && (error is IOException || error is ApiException && error.retryAllowed!=false && (error.ambiguous || isCsrfRejection(error.status,error.message.orEmpty()) || error.status in listOf(408,429,500,502,503,504)))

/** A report that can simply be tried again; its message is already plain and complete. */
internal class ReportRetryException(message:String):java.io.IOException(message)

internal fun clinicianError(error:Exception,purpose:String?=null):String {
    if(error is ReportRetryException)return error.message.orEmpty()
    val api=error as? ApiException
    val task=when(purpose){"audio"->"Recording processing";"report"->"Report generation";"photo"->"Photo assessment";"consultant"->"Ask Consultant";else->"Request"}
    var code=api?.category
    val reason=when {
        error is java.net.SocketTimeoutException -> "The connection was too slow. Your work is saved on this phone. Tap Try again."
        error is java.net.UnknownHostException -> "No internet connection. Your work is saved on this phone. Tap Try again when you're back online."
        error is javax.net.ssl.SSLException -> {code="TLS_CONNECTION_FAILED";"The secure connection could not be verified. Check the device date and contact support if it persists."}
        error is java.net.ConnectException -> "Couldn't reach the server. Your work is saved on this phone. Tap Try again."
        error is java.net.SocketException -> "The connection dropped while sending. Your work is saved on this phone. Tap Try again."
        code=="PROVIDER_QUOTA" -> "The AI provider has insufficient funds or a spending limit. An administrator must check the provider account."
        code=="PROVIDER_AUTH" -> "The provider rejected the API key or account access. An administrator must check provider credentials and permissions."
        code=="MODEL_UNAVAILABLE" -> "No provider endpoint matches the configured model and privacy settings. An administrator must check routing."
        code=="REQUEST_INCOMPATIBLE" -> "The provider rejected the request format or model parameters. The integration needs correction."
        code=="PROVIDER_TIMEOUT" -> "The AI service took too long this time. Your work is saved. Tap Try again."
        code=="OUTPUT_LIMIT" -> "The AI response was cut short. No partial result was accepted. Your source is saved; contact support before trying again."
        code=="INVALID_RESPONSE" -> "The AI response failed structure or clinical-data validation. Its result was not accepted."
        code=="NETWORK_UNAVAILABLE" -> "The server could not connect to the AI provider. Retry when that connection is restored."
        code=="RATE_LIMITED" -> "The provider rate-limited this request. Wait before retrying."
        code=="RETRY_LIMIT" -> "The retry limit for this request has been reached. Contact support before starting another attempt."
        code=="RESPONSE_PROCESSING_FAILED" -> "The server could not process the provider response. Contact support with the request reference."
        code=="PROVIDER_UNAVAILABLE" -> "The AI service didn't respond this time. Your work is saved. Tap Try again."
        api!=null && isCsrfRejection(api.status,api.message.orEmpty()) -> {code="SESSION_TOKEN_REFRESH_FAILED";"The secure session token could not be refreshed. Check your connection and tap Try Again. Your recording is not affected."}
        api?.status==429 -> {code="RATE_LIMITED";"This request is temporarily limited. Wait before trying again."}
        api?.status==401 -> "Your session is no longer authorized. Sign in again."
        api?.status==402 -> "This app account needs AI entitlement or app credits. Provider funding alone does not activate app access."
        api?.status==428 -> "Review the updated terms to continue."
        api!=null -> api.message.orEmpty().takeIf{it.isNotBlank() && it.length<=1200 && !Regex("(?i)traceback|input_value|sk-[a-z0-9]|bearer |<html|<!doctype").containsMatchIn(it)} ?: when(api.status){
            403->"You do not have permission for this action."
            409->"The request conflicts with the current state. Refresh before trying again."
            413->"The upload exceeds the server size limit. Choose a smaller file."
            415->"The file format is not supported. Choose a supported format."
            422->"The server rejected the submitted fields. Review the input."
            429->"The application request limit was reached. Wait before retrying."
            500->"The application server encountered an internal error. Contact support with the request reference."
            502->"The AI service didn't respond this time. Your work is saved. Tap Try again."
            503->"The service is busy right now. Your work is saved. Tap Try again in a moment."
            504->"This took too long. Your work is saved. Tap Try again."
            else->"The request failed without a recognized cause. Contact support with the request reference."
        }
        error is IOException -> {code="LOCAL_OR_NETWORK_IO_FAILED";"A read or write operation failed. Check connectivity and available device storage."}
        error is IllegalArgumentException -> error.message.orEmpty().takeIf{it.isNotBlank() && it.length<=500 && !Regex("(?i)sk-|bearer |input_value|traceback").containsMatchIn(it)} ?: "The input or local request state is invalid. Review your selections."
        else -> {code="UNEXPECTED_APP_ERROR";"An unexpected application error occurred. Contact support with the request reference."}
    }
    val technical=listOfNotNull(code,api?.status?.let{"HTTP $it"},api?.upstreamStatus?.let{"provider HTTP $it"},api?.requestId?.takeIf{runCatching{java.util.UUID.fromString(it).toString()==it}.getOrDefault(false)}?.let{"Request $it"})
    return "$task: $reason" + if(technical.isEmpty())""else "\n"+technical.joinToString(" · ")
}

/** Operational failures use a compact, neutral surface. Clinical alerts keep their own treatment. */
@Composable fun RequestFeedback(message:String,retryAtMillis:Long=0,onRetry:(()->Unit)?=null,onDismiss:()->Unit){
    var ready by remember(retryAtMillis){mutableStateOf(System.currentTimeMillis()>=retryAtMillis)}
    LaunchedEffect(retryAtMillis){if(!ready){delay((retryAtMillis-System.currentTimeMillis()).coerceAtLeast(0));ready=true}}
    val c=MaterialTheme.colorScheme
    Surface(color=c.surfaceContainerLow,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).semantics{liveRegion=LiveRegionMode.Polite}){
        Column(Modifier.padding(horizontal=16.dp,vertical=10.dp)){
            Text(message,style=MaterialTheme.typography.bodyMedium,color=c.onSurface)
            Row(Modifier.align(Alignment.End)){
                TextButton(onClick=onDismiss){Text("Dismiss")}
                onRetry?.let{TextButton(onClick=it,enabled=ready){Text(if(ready)"Try Again"else"Please wait…")}}
            }
        }
    }
}

/** Standing review reminders repeated by the server after every step. The clinician reviews every draft anyway,
 *  so these are not shown as a banner; specific, actionable warnings still are. */
private val ROUTINE_REMINDERS = listOf(
    "review negation", "review the recognized message", "no message was sent", "review the transcript against the recording",
    "photo observations require clinical correlation", "draft — clinician review required", "draft for clinician review",
    "review the recognized correction", "source statements and ai suggestions are not automatically confirmed",
)
internal fun routineReminder(message: String): Boolean = message.trim().lowercase().let { text -> ROUTINE_REMINDERS.any { text.startsWith(it) } }
