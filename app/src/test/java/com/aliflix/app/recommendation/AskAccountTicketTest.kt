package com.aliflix.app.recommendation
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AskAccountTicketTest {
 @Test fun accountHandoffCannotSendPreviousLibraryUnderANewIdentity(){
  val a=AskAccountTicket("a","user:a",4)
  a.requireCurrent(a)
  for(next in listOf(AskAccountTicket("b","user:b",5),AskAccountTicket("a","user:a",6),AskAccountTicket("a","user:b",4)))
   assertThrows(CancellationException::class.java){a.requireCurrent(next)}
  assertThrows(CancellationException::class.java){AskAccountTicket("b","user:a",4).let{it.requireCurrent(it)}}
 }
}
