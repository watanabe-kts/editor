package controllers

import java.time.ZonedDateTime

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.{Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Merge, MergeHub, Sink}
import akka.stream.{FlowShape, KillSwitches, Materializer}
import javax.inject._
import models.Account
import play.api._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.streams.ActorFlow
import play.api.mvc._

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * Web Socket
  *
  * 書き直し中...
  *
  * @param system
  * @param materializer
  * @param cc
  */
@Singleton
class WsController @Inject()(implicit system: ActorSystem, materializer: Materializer, cc: ControllerComponents) extends AbstractController(cc) {
  import GraphDSL.Implicits._

  val MAX_LINE_LENGTH = 100

  var titleMock = "No_Title"
  case class Line(id: Long, text: String, writer: String, date: ZonedDateTime)
  val linesMock = mutable.ListBuffer {
    Line(0, "", "", DateUtil.now)
  }

  sealed trait WsAction

  case class FailAction(session: Option[models.Session], pageToken: Option[String], message: String) extends WsAction
  case class NoneAction() extends WsAction

  sealed trait RoomAction extends WsAction

  sealed trait RoomBroadcastAction extends RoomAction
  case class InsertAction(session: Option[models.Session], pageToken: Option[String], id: Long, prevId: Long, text: String) extends RoomBroadcastAction
  case class UpdateAction(session: Option[models.Session], pageToken: Option[String], id: Long, text: String) extends RoomBroadcastAction
  case class DeleteAction(session: Option[models.Session], pageToken: Option[String], id: Long) extends RoomBroadcastAction

  sealed trait RoomSingleAction extends RoomAction
  case class GetAllAction(session: Option[models.Session], pageToken: Option[String]) extends RoomSingleAction
  case class TryAgainAction(session: Option[models.Session], pageToken: Option[String], targetAction: String) extends RoomSingleAction

  def roomProcess(json: JsValue): WsAction = {
    val v = (json \ "received").get
    val token = (json \ "token").asOpt[String]
    val session = token.flatMap(Account.getSession)
    val action = (v \ "action").asOpt[String]
    val pageToken = (v \ "pageToken").asOpt[String]
    val res: WsAction = action match {
      case Some("insert") => {
        val id = (v \ "id").asOpt[Long].getOrElse(-1l)
        val prevId = (v \ "prevId").asOpt[Long].getOrElse(-1l)
        val text = (v \ "text").asOpt[String].getOrElse("")
        val index = linesMock.indexWhere(line => line.id == prevId)
        if (text.length <= MAX_LINE_LENGTH) {
          val writer = session.map(_.account.name).getOrElse("Anonymous")
          val newLine = Line(id, text, writer, DateUtil.now)
          if (index >= 0) {
            if (index < linesMock.size) {
              linesMock.insert(index + 1, newLine)
            } else {
              linesMock += newLine
            }
            InsertAction(session, pageToken, id, prevId, text)
          } else {
            TryAgainAction(session, pageToken, "insert")
            // FailAction(session, "Error")
          }
        } else {
          FailAction(session, pageToken, "Error")
        }
      }
      case Some("update") => {
        val id = (v \ "id").asOpt[Long].getOrElse(-1l)
        val text = (v \ "text").asOpt[String].getOrElse("")
        val index = linesMock.indexWhere(line => line.id == id)
        if (text.length <= MAX_LINE_LENGTH) {
          if (index >= 0) {
            val writer = session.map(_.account.name).getOrElse("Anonymous")
            linesMock(index) = Line(id, text, writer, DateUtil.now)
            UpdateAction(session, pageToken, id, text)
          } else {
            FailAction(session, pageToken, "Error")
          }
        } else {
          FailAction(session, pageToken, "Error")
        }
      }
      case Some("delete") => {
        val id = (v \ "id").asOpt[Long].getOrElse(-1l)
        val index = linesMock.indexWhere(line => line.id == id)
        if (index >= 0) {
          linesMock.remove(index)
          DeleteAction(session, pageToken, id)
        } else {
          FailAction(session, pageToken, "Error")
        }
      }
      case Some("get-all") => {
        GetAllAction(session, pageToken)
      }
      case Some(_) => {
        FailAction(session, pageToken, "Error")
      }
      case None => {
        NoneAction()
      }
    }
    // Logger.debug(linesMock.mkString(", "))
    res
  }

  def roomSingleFilter(a: WsAction): Boolean = a match {
    case _: RoomSingleAction => true
    case _ => false
  }

  def roomBroadcastFilter(a: WsAction): Boolean = a match {
    case _: RoomBroadcastAction => true
    case _ => false
  }

  def roomBroadcastResponse(action: WsAction): JsValue = action match {
    case InsertAction(session, pageToken, id, prevId, text) => Json.obj(
      "action" -> "insert",
      "writer" -> session.map(_.account.name),
      "pageToken" -> pageToken,
      "id" -> id,
      "prevId" -> prevId,
      "text" -> text,
      "insertedAt" -> DateUtil.formattedNow
    )
    case UpdateAction(session, pageToken, id, text) => Json.obj(
      "action" -> "update",
      "writer" -> session.map(_.account.name),
      "pageToken" -> pageToken,
      "id" -> id,
      "text" -> text,
      "updatedAt" -> DateUtil.formattedNow
    )
    case DeleteAction(session, pageToken, id) => Json.obj(
      "action" -> "delete",
      "writer" -> session.map(_.account.name),
      "pageToken" -> pageToken,
      "id" -> id,
      "deletedAt" -> DateUtil.formattedNow
    )
  }

  def roomSingleResponse(action: WsAction): JsValue = action match {
    case GetAllAction(session, pageToken) => Json.obj(
      "action" -> "get-all",
      "token" -> session.map(_.token),
      "pageToken" -> pageToken,
      "title" -> titleMock,
      "lines" -> JsArray(linesMock.map(l => Json.obj(
        "id" -> l.id,
        "text" -> l.text,
        "writer" -> l.writer,
        "date" -> DateUtil.format(l.date)
      ))),
      "createdAt" -> DateUtil.formattedNow
    )
    case TryAgainAction(session, pageToken, targetAction) => Json.obj(
      "action" -> "try-again",
      "token" -> session.map(_.token),
      "pageToken" -> pageToken,
      "targetAction" -> targetAction
    )
  }

  val bus = {
    val (busSink, busSource) =
      MergeHub.source[JsValue]
        .toMat(BroadcastHub.sink)(Keep.both)
        .run

    busSource.runWith(Sink.ignore)

    Flow.fromSinkAndSource(busSink, busSource)
      .joinMat(KillSwitches.singleBidi[JsValue, JsValue])(Keep.right)
      .backpressureTimeout(10.seconds)
  }

  def ws: WebSocket = WebSocket.accept[JsValue, JsValue] { request =>
    Logger.debug("ws: connected")

    val token = request.session.get("token")

    val inFlow  = ActorFlow.actorRef(out => RequestActor.props(out))

    val outFlow = ActorFlow.actorRef(out => ResponseActor.props(out))

    val graph = GraphDSL.create() { implicit b =>
      val i = b.add(inFlow)
      val o = b.add(outFlow)

      val bcast = b.add(Broadcast[WsAction](2))
      val merge = b.add(Merge[JsValue](2))

      val preProc = b.add(Flow[JsValue]
        .map(json => Json.obj(
          "received" -> json,
          "token" -> token
        ))
        .map(roomProcess))
      val roomRes = b.add(Flow[WsAction].filter(roomBroadcastFilter).map(roomBroadcastResponse))

      val roomBus = b.add(bus)

      val singleRes = b.add(Flow[WsAction].filter(roomSingleFilter).map(roomSingleResponse))

      i ~> preProc ~> bcast ~> roomRes ~> roomBus ~> merge ~> o
                      bcast ~> singleRes          ~> merge

      FlowShape(i.in, o.out)
    }
    Flow.fromGraph(graph)
  }

  object RequestActor {
    def props(out: ActorRef) = Props(new RequestActor(out))
  }

  class RequestActor(out: ActorRef) extends Actor {
    override def receive: Receive = {
      case msg: JsValue => out ! msg
    }
  }

  object ResponseActor {
    def props(out: ActorRef) = Props(new ResponseActor(out))
  }

  class ResponseActor(out: ActorRef) extends Actor {
    override def receive: Receive = {
      case msg: JsValue => out ! msg
    }
  }
}

object DateUtil {
  import java.time._
  import java.time.format.DateTimeFormatter._

  def now = ZonedDateTime.now

  def format(d: ZonedDateTime) = d.format(RFC_1123_DATE_TIME)

  def formattedNow = format(now)
}