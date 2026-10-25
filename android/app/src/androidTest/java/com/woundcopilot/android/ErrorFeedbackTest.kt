package com.woundcopilot.android

import org.junit.Test
import org.junit.Assert.*

class ErrorFeedbackTest {
 @Test fun validationErrorsDoNotEchoSubmittedValues(){
  val payload=obj("detail" to org.json.JSONArray().put(obj("msg" to "Invalid SYNTHETIC_PRIVATE_SOURCE", "input" to "sk-secret")))
  val result=apiErrorMessage(payload)
  assertTrue(result.contains("REQUEST_VALIDATION_FAILED"));assertFalse(result.contains("SYNTHETIC_PRIVATE_SOURCE"));assertFalse(result.contains("sk-secret"))
 }
 @Test fun providerFailuresRemainDistinctAcrossTasks(){
  val codes=listOf("PROVIDER_QUOTA","PROVIDER_AUTH","MODEL_UNAVAILABLE","REQUEST_INCOMPATIBLE","PROVIDER_TIMEOUT","INVALID_RESPONSE","NETWORK_UNAVAILABLE","RATE_LIMITED","RETRY_LIMIT","RESPONSE_PROCESSING_FAILED")
  for(task in listOf("audio","report","photo","consultant")){
   val messages=codes.map{clinicianError(ApiException(503,"",category=it),task)}
   assertEquals(codes.size,messages.toSet().size)
   messages.forEach{assertFalse(it.contains("Your work is saved"))}
  }
 }
 @Test fun technicalReferenceAndUpstreamStatusAreAvailableOnFirstError(){
  val id="a2bf8c15-94b0-4b61-a75c-3bc1d8a93c4b"
  val message=clinicianError(ApiException(503,"",requestId=id,category="PROVIDER_AUTH",upstreamStatus=401),"report")
  assertTrue(message.contains(id));assertTrue(message.contains("provider HTTP 401"));assertTrue(message.contains("PROVIDER_AUTH"))
 }
 @Test fun networkFailuresAreNotCalledAiFailures(){
  assertTrue(clinicianError(java.net.UnknownHostException("private host")).contains("DNS_LOOKUP_FAILED"))
  assertTrue(clinicianError(java.net.SocketTimeoutException()).contains("NETWORK_TIMEOUT"))
  val ssl=javax.net.ssl.SSLHandshakeException("private certificate")
  assertTrue(clinicianError(ssl).contains("TLS_CONNECTION_FAILED"));assertFalse(canRetryRequest(ssl))
 }
 @Test fun unexpectedExceptionDoesNotLeakContentOrClaimSaved(){
  val message=clinicianError(IllegalStateException("SYNTHETIC_PRIVATE_SOURCE sk-secret"),"audio")
  assertFalse(message.contains("SYNTHETIC_PRIVATE_SOURCE"));assertFalse(message.contains("sk-secret"));assertFalse(message.contains("saved"))
 }
 @Test fun permanentProviderFailureDoesNotOfferRetry(){
  assertFalse(canRetryRequest(ApiException(503,"",category="PROVIDER_AUTH",retryAllowed=false)))
 }
 @Test fun usefulServerExplanationIsNotSuppressedByProviderKeyword(){
  assertTrue(clinicianError(ApiException(503,"Live AI is paused until the owner activates provider execution"),"report").contains("Live AI is paused"))
 }
}
