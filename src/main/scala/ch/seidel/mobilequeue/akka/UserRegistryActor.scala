package ch.seidel.mobilequeue.akka

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import authentikat.jwt.JsonWebToken
import authentikat.jwt.JwtHeader
import authentikat.jwt.JwtClaimsSet
import ch.seidel.mobilequeue.app.Config
import ch.seidel.mobilequeue.model._
import ch.seidel.mobilequeue.akka.EventRegistryActor.EventCreated
import ch.seidel.mobilequeue.akka.EventRegistryActor.ConnectEventUser
import ch.seidel.mobilequeue.akka.EventRegistryActor.EventUpdated
import ch.seidel.mobilequeue.akka.EventRegistryActor.EventDeleted
import ch.seidel.mobilequeue.http.JwtSupport

object UserRegistryActor {
  sealed trait UserRegistryMessage
  final case class ActionPerformed(user: User, description: String) extends UserRegistryMessage
  final case class Authenticate(username: String, password: String, deviceId: String) extends UserRegistryMessage
  final case class ClientConnected(user: User, deviceId: String, client: ActorRef) extends UserRegistryMessage

  final case object GetUsers extends UserRegistryMessage
  final case class CreateUser(user: User) extends UserRegistryMessage
  final case class GetUser(id: Long) extends UserRegistryMessage
  final case class DeleteUser(id: Long) extends UserRegistryMessage

  sealed trait PropagateUserEvent {
    val user: User
  }
  final case class UserCreated(user: User) extends PropagateUserEvent
  final case class UserDeleted(user: User) extends PropagateUserEvent
  final case class UserChanged(user: User) extends PropagateUserEvent

  sealed trait PropagateTicketMessage {
    val msg: MobileTicketQueueEvent
    val ticketActor: ActorRef
  }
  final case class PropagateTicketIssued(msg: TicketIssued, ticketActor: ActorRef) extends PropagateTicketMessage
  final case class PropagateTicketCalled(msg: TicketCalled, ticketActor: ActorRef) extends PropagateTicketMessage
  final case class PropagateTicketConfirmed(msg: TicketConfirmed, ticketActor: ActorRef) extends PropagateTicketMessage
  final case class PropagateTicketSkipped(msg: TicketSkipped, ticketActor: ActorRef) extends PropagateTicketMessage
  final case class PropagateTicketClosed(msg: TicketClosed, ticketActor: ActorRef) extends PropagateTicketMessage

  def props(eventRegistry: ActorRef): Props = Props(classOf[UserRegistryActor], eventRegistry)
}

class UserRegistryActor(eventRegistry: ActorRef) extends Actor with JwtSupport with Config /*with ActorLogging*/ {
  import UserRegistryActor._
  import context._

  def isUnique(username: String, deviceId: String)(implicit users: Map[User, Set[ActorRef]]) =
    users.keys.forall(u => !(u.name == username && u.deviceIds.contains(deviceId)))

  def createUser(user: User)(implicit users: Map[User, Set[ActorRef]]): User = {
    val withId = user.copy(id = users.keys.foldLeft(0L)((acc, user) => { math.max(user.id, acc) }) + 1L)
    watch(sender)
    val newUserSet = users + (withId -> Set(sender))
    //    println("new User-set " + newUserSet)
    become(operateWith(newUserSet))
    withId
  }

  def broadcastToAllClients(userid: Long, message: PropagateTicketMessage)(implicit users: Map[User, Set[ActorRef]]) {
    users.filter(u => u._1.id == userid).map(u => {
      //      println("forwarding " + message + " for " + u._1 + " to " + u._2)
      u
    }).flatMap(_._2).filter(_ != sender).foreach(_ ! message)
  }

  def printUserActorRefs(implicit users: Map[User, Set[ActorRef]]) {
    println("operateWith:" + users.map(u => (u._1, u._2.mkString("\n   * ", "\n   * ", ""))).mkString("\n - ", "\n - ", ""))
  }

  def responseWithAccessToken(user: User, deviceId: String): UserAuthenticated = {
    val claims = setClaims(user.id, jwtTokenExpiryPeriodInDays)
    UserAuthenticated(user, deviceId, JsonWebToken(jwtHeader, claims, jwtSecretKey))
  }

  def operateWith(implicit users: Map[User, Set[ActorRef]]): Receive = {
    case Authenticate(name, pw, deviceId) =>
      if (isUnique(name, deviceId)) {
        val withId = createUser(User(0, Set(deviceId), name, pw, "", ""))
        sender ! responseWithAccessToken(withId.withHiddenPassword, deviceId)
        eventRegistry ! ClientConnected(withId, deviceId, sender)
        println("user created " + withId + " with clientActor " + sender)
      } else {
        users.keys.filter(u => u.name == name && ((u.password.nonEmpty && u.password == pw) || u.deviceIds.contains(deviceId))).headOption match {
          case Some(user) =>
            watch(sender)
            val udr = user.copy(deviceIds = user.deviceIds + deviceId, password = if (pw.nonEmpty) pw else user.password)
            val newUserSet = users - user + (udr -> (users(user) + sender))
            //            println("new User-set " + newUserSet)
            become(operateWith(newUserSet))

            // qualify the acting deviceId to propagate ClientConnected in the system
            val ud = udr.copy(deviceIds = Set(deviceId)).withHiddenPassword
            sender ! responseWithAccessToken(ud.withHiddenPassword, deviceId)
            eventRegistry ! ClientConnected(ud, deviceId, sender)
            println("user authenticated " + ud + " with clientActor " + sender)
          case _ =>
            sender ! UserAuthenticationFailed(UserDefaults.empty(name), deviceId, pw == "")
        }
      }

    case Terminated(clientActor) =>
      println("UserRegistry: unwatching " + clientActor)
      unwatch(clientActor)
      users
        .filter(userToActor => userToActor._2.contains(clientActor))
        .foreach { userToActor =>
          val (user, clientActorList) = userToActor
          //          println(s"disconnecting client-actor of ${clientActorList.size} from user ${user.withHiddenPassword}.")
          val newClientActorList = clientActorList - clientActor
          //          println(s"remaining registered clients on User ${user}: ${newClientActorList.size}")
          val newUserList = users - user + (user -> newClientActorList)
          //          println("new Userlist: " + newUserList)
          become(operateWith(newUserList))
        }

    // forwarding to all clients of a user      
    case ti: PropagateTicketIssued => broadcastToAllClients(ti.msg.ticket.userid, ti)
    case ti: PropagateTicketCalled => broadcastToAllClients(ti.msg.ticket.userid, ti)
    case ti: PropagateTicketConfirmed => broadcastToAllClients(ti.msg.ticket.userid, ti)
    case ti: PropagateTicketSkipped => broadcastToAllClients(ti.msg.ticket.userid, ti)
    case ti: PropagateTicketClosed => broadcastToAllClients(ti.msg.ticket.userid, ti)

    // connect with actual logged in owner
    case ec @ EventCreated(event) =>
      users
        .filter(userToActor => userToActor._1.id == event.userid)
        .flatMap(_._2).foreach(eventRegistry ! ConnectEventUser(event.userid, _))
    case ec @ EventUpdated(event) =>
      users
        .flatMap(_._2).foreach(_ ! ec)
    case ec @ EventDeleted(id) =>
      users
        .flatMap(_._2).foreach(_ ! ec)

    // Rest-API
    case GetUsers =>
      sender ! Users(users.keys.toSeq.map(_.withHiddenPassword.withHiddenDeviceIds))

    case CreateUser(user) =>
      if (isUnique(user.name, user.deviceIds.headOption.getOrElse(UUID.randomUUID().toString()))) {
        val withId = createUser(user)
        sender ! ActionPerformed(withId.withHiddenPassword, s"User ${withId.name} with id ${withId.id} created.")
      } else {
        sender ! ActionPerformed(UserDefaults.empty(user.name), s"User ${user.name} exists already.")
      }

    case GetUser(id) =>
      sender ! users.keys.find(_.id == id).map(_.withHiddenPassword.withHiddenDeviceIds)

    case DeleteUser(id) =>
      val toDelete = users.keys.find(_.id == id).getOrElse(UserDefaults.empty(id))
      toDelete match {
        case User(id, _, _, _, _, _) if (id > 0) =>
          become(operateWith(users - toDelete))
        case _ =>
      }
      sender ! ActionPerformed(toDelete.withHiddenPassword.withHiddenDeviceIds, s"User ${id} deleted.")
  }

  def receive = operateWith(Map.empty[User, Set[ActorRef]])
}
