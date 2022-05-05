package com.krmali.bank.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import java.util.UUID
import akka.NotUsed
import scala.concurrent.duration._
import akka.util.Timeout
import akka.actor.typed.Scheduler
import scala.concurrent.ExecutionContext
import akka.actor.typed.ActorSystem

object Bank{
   // commands
   import PersistentBankAccount.Command._
   import PersistentBankAccount.Response._
   import PersistentBankAccount.Command
    
   // events
   sealed trait Event
   case class BankAccountCreated(id: String) extends Event

   // state
   case class State(accounts: Map[String, ActorRef[Command]])

   // command handler
   def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>
     command match{
       case createCommand @ CreateBankAccount(_,_,_,_) =>
         val id = UUID.randomUUID().toString
         val newBankAccount = context.spawn(PersistentBankAccount(id),id)
         Effect.persist(BankAccountCreated(id))
           .thenReply(newBankAccount)(_ => createCommand)
       case updateCommand @ UpdateBalance(id, _, _, replyTo) =>
         state.accounts.get(id) match{
           case Some(account) =>
             Effect.reply(account)(updateCommand)
           case None =>
             Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
         }
           case getCmd @ GetBankAccount(id, replyTo) =>
             state.accounts.get(id) match{
               case None =>
                 Effect.reply(replyTo)(GetBankAccountResponse(None))
               case Some(account) =>
                 Effect.reply(account)(getCmd)
             }
     } 
   // event handler
   def eventHandler(context: ActorContext[Command]) : (State, Event) => State = (state, event) =>
     event match{
       case BankAccountCreated(id) =>
         val account = context.child(id)
         .getOrElse(context.spawn(PersistentBankAccount(id),id))
         .asInstanceOf[ActorRef[Command]]
         state.copy(accounts = state.accounts + (id -> account))
     }


   def apply(): Behavior[Command] = Behaviors.setup { context =>
     EventSourcedBehavior[Command, Event, State](
       persistenceId = PersistenceId.ofUniqueId("bank"),
       emptyState = State(Map()),
       eventHandler = eventHandler(context),
       commandHandler = commandHandler(context)
       )
   }

}

// object BankPlayground{
//   import PersistentBankAccount.Command._
//   import PersistentBankAccount.Response._
//   def main(args: Array[String]): Unit = {
//     val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
//       val bank = context.spawn(Bank(), "bank")
//
//       // ask pattern
//       import akka.actor.typed.scaladsl.AskPattern._
//       implicit val timeout: Timeout = Timeout(2.seconds)
//       implicit val scheduler: Scheduler = context.system.scheduler
//       implicit val ec: ExecutionContext = context.executionContext
//
//       bank.ask(replyTo => CreateBankAccount("kareem", "usd", 100, replyTo)).flatMap{ 
//         case BankAccountCreatedResponse(id) =>
//           context.log.info(s"successfully created new bank account: $id")
//           bank.ask(replyTo => GetBankAccount(id, replyTo))
//       }.foreach {
//         case GetBankAccountResponse(maybeBankAccount) =>
//           context.log.info(s"account details: $maybeBankAccount")
//       }
//
//       Behaviors.empty
//     }
//     val system = ActorSystem(rootBehavior, "BankDemo")
//   }
// }

object BankPlayground {
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._
  import PersistentBankAccount.Response

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val bank = context.spawn(Bank(), "bank")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response]{
        case BankAccountCreatedResponse(id) =>
          logger.info(s"successfully created bank account $id")
          Behaviors.same
        case GetBankAccountResponse(maybeBankAccount) =>
          logger.info(s"Account details: $maybeBankAccount")
          Behaviors.same
      }, "replyHandler")

      // ask pattern
      import akka.actor.typed.scaladsl.AskPattern._
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext

           // bank ! CreateBankAccount("kareem", "USD", 1000, responseHandler)
           bank ! GetBankAccount("997806f5-3aa4-4a4e-bab8-3d323d6f94b3", responseHandler)

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "BankDemo")
  }
}
