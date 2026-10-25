package com.woundcopilot.wear.contract

/** Late async discovery must not revive a paused screen or replace a newer peer. */
class ConnectionEpoch {
 private var generation=0L
 private var active=false
 fun resume(){active=true;generation++}
 fun pause(){active=false;generation++}
 fun request():Long?=if(active)++generation else null
 fun accepts(ticket:Long)=active && ticket==generation
}

/** A confirmation belongs to the job the user actually saw, never a replacement recording. */
fun confirmationAllowed(type:CommandType,job:String?,status:WearStatus?,ready:Boolean):Boolean {
 if(!ready||status==null||job!=status.job)return false
 return when(type){
  CommandType.CANCEL->status.phase in setOf(Phase.RECORDING,Phase.PAUSED)
  CommandType.START->status.phase in setOf(Phase.IDLE,Phase.CANCELLED,Phase.REPORT_READY)
  else->false
 }
}
