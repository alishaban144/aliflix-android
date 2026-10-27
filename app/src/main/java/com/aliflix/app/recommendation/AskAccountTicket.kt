package com.aliflix.app.recommendation

import kotlinx.coroutines.CancellationException

data class AskAccountTicket(val owner:String,val scope:String,val revision:Long) {
    val aligned get()=scope==if(owner=="guest")"guest" else "user:$owner"
    fun requireCurrent(current:AskAccountTicket){
        if(this!=current||!aligned)throw CancellationException("Account changed")
    }
}

class AskAccountScope(context:android.content.Context){
    private val snapshots=com.aliflix.app.account.AccountLocalSnapshotStore(context)
    fun current()=AskAccountTicket(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?:"guest",snapshots.activeScope,snapshots.scopeRevision)
    fun changes()=snapshots.scopeChanges()
}
