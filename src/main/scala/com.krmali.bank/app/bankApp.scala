package `com.krmali.bank`.app

import akka.actor.typed.ActorRef
import com.krmali.bank.actors.PersistentBankAccount.Command._
import akka.actor.typed.Behavior
import com.krmali.bank.actors.Bank
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import com.krmali.bank.http.BankRouter
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import scala.util.{Try, Success, Failure}

object BankApp{
  
  def startHttpServer(bank: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {

    val router = new BankRouter(bank)
    val routes: Route = router.routes

    implicit val ec1: ExecutionContext = system.executionContext
    val httpBindingFuture = Http().newServerAt("localhost", 8888).bind(routes)
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val addr = binding.localAddress
        system.log.info(s"Server started at ${addr.getHostString}: ${addr.getPort} ")
      case Failure(ex) =>
        system.log.error(s"Failed to start http server, because: $ex")
        system.terminate()
    }
  }


  def main(args: Array[String]): Unit = {
    trait RootCommand
    case class RetrieveBankActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

    val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context =>
      val bankActor: ActorRef[Command] = context.spawn(Bank(), "bank")
      Behaviors.receiveMessage{
        case RetrieveBankActor(replyTo) =>
          replyTo ! bankActor
          Behaviors.same
      }
    }
    
    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "BankSystem")
    implicit val ec: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout(5.seconds)
    val bankActorFuture: Future[ActorRef[Command]] = system.ask(replyTo => RetrieveBankActor(replyTo))
    bankActorFuture.foreach(startHttpServer)
  }
}
