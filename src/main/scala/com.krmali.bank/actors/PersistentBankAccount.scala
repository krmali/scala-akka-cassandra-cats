package com.krmali.bank.actors

import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl.Effect
import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.PersistenceId

object PersistentBankAccount {

  // commands = messages
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._
  object Command{
    sealed trait Command
    case class CreateBankAccount(user: String, currency: String, initialBalance: Double, replyTo: ActorRef[Response]) extends Command
    case class UpdateBalance(id: String, currency: String, amount: Double, replyTo: ActorRef[Response]) extends Command
    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }
  // events = to presist to Cassandra
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Double) extends Event

  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Double)

  // responses
  object Response{
    sealed trait Response
    case class BankAccountCreatedResponse(id: String) extends Response
    case class BankAccountBalanceUpdatedResponse(mayBankAccount: Option[BankAccount]) extends Response
    case class GetBankAccountResponse(mayBankAccount: Option[BankAccount]) extends Response
  }

  //actor -> command handler = message handler
  //      \
  //        event handler = update state

  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match{
      case CreateBankAccount(user, currency, initialBalance, replyTo) =>
        val id = state.id
        Effect.persist(BankAccountCreated(BankAccount(id = id, user, currency, initialBalance))) // will persist to Cassandra
          .thenReply(replyTo)(_ => BankAccountCreatedResponse(id))
      case UpdateBalance(_, _, amount, replyTo) =>
        val newBalance = state.balance + amount
        if (newBalance < 0)
          Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
        else
            Effect.persist(BalanceUpdated(amount))
          .thenReply(replyTo)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))
      case GetBankAccount(_, replyTo) =>
        Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))
    }

    val eventHandler: (BankAccount, Event) => BankAccount = (bankAccount, event) =>
      event match{
        case BankAccountCreated(bankAccount) => bankAccount
        case BalanceUpdated(amount) =>
          bankAccount.copy(balance = bankAccount.balance + amount)
      }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0), //unused 
      commandHandler = commandHandler,
      eventHandler = eventHandler
      )

}
