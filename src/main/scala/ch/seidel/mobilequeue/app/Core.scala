package ch.seidel.mobilequeue.app

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.server.HttpApp

import akka.stream.ActorMaterializer

import scala.concurrent.{ ExecutionContext, Future }
import scala.io.StdIn
import com.typesafe.config.ConfigFactory

import ch.seidel.mobilequeue.akka._

object Core {
  /**
   * Construct the ActorSystem we will use in our application
   */
  implicit lazy val system = ActorSystem("mobileQueueHttpServer")
  // Needed for the Future and its methods flatMap/onComplete in the end
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  val eventRegistryActor: ActorRef = system.actorOf(EventRegistryActor.props, "eventRegistryActor")
  val userRegistryActor: ActorRef = system.actorOf(UserRegistryActor.props(eventRegistryActor), "userRegistryActor")

}

trait BootedCore {
  import Core._

  /**
   * Ensure that the constructed ActorSystem is shut down when the JVM shuts down
   */
  sys.addShutdownHook(shutDown())

  def shutDown() {
    system.terminate()
  }
}
