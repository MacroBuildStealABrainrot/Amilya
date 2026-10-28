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

internal fun clinicianError(error:Exception,purpose:String?=null):String {
    val api=error as? ApiException
    val task=when(purpose){"audio"->"Recording processing";"report"->"Report generation";"photo"->"Photo assessment";"consultant"->"Wound Consultant";else->"Request"}
    var code=api?.category
    val reason=when {
        error is java.net.SocketTimeoutException -> {code="NETWORK_TIMEOUT";"The connection timed out. Check request status before retrying; the server may still be processing."}
        error is java.net.UnknownHostException -> {code="DNS_LOOKUP_FAILED";"The server address could not be resolved. Check your internet connection or DNS settings."}
        error is javax.net.ssl.SSLException -> {code="TLS_CONNECTION_FAILED";"The secure connection could not be verified. Check the device date and contact support if it persists."}
        error is java.net.ConnectException -> {code="CONNECTION_REFUSED";"A connection to the server could not be established. Check connectivity and server availability."}
        error is java.net.SocketException -> {code="CONNECTION_INTERRUPTED";"The connection was interrupted. Check request status before retrying."}
        code=="PROVIDER_QUOTA" -> "The AI provider has insufficient funds or a spending limit. An administrator must check the provider account."
        code=="PROVIDER_AUTH" -> "The provider rejected the API key or account access. An administrator must check provider credentials and permissions."
        code=="MODEL_UNAVAILABLE" -> "No provider endpoint matches the configured model and privacy settings. An administrator must check routing."
        code=="REQUEST_INCOMPATIBLE" -> "The provider rejected the request format or model parameters. The integration needs correction."
        code=="PROVIDER_TIMEOUT" -> "The AI provider timed out. Check request status before retrying."
        code=="OUTPUT_LIMIT" -> "The AI response was cut short. No partial result was accepted. Your source is saved; contact support before trying again."
        code=="INVALID_RESPONSE" -> "The AI response failed structure or clinical-data validation. Its result was not accepted."
        code=="NETWORK_UNAVAILABLE" -> "The server could not connect to the AI provider. Retry when that connection is restored."
        code=="RATE_LIMITED" -> "The provider rate-limited this request. Wait before retrying."
        code=="RETRY_LIMIT" -> "The retry limit for this request has been reached. Contact support before starting another attempt."
        code=="RESPONSE_PROCESSING_FAILED" -> "The server could not process the provider response. Contact support with the request reference."
        code=="PROVIDER_UNAVAILABLE" -> "The provider returned an unsuccessful response. Check the request diagnostics before retrying."
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
            502->"The server received an invalid upstream response. Check the request diagnostics."
            503->"The server could not service this request. Check the request diagnostics for the cause."
            504->"The server deadline expired. Check request status before retrying."
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
