package controllers

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.{Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Merge, MergeHub, Sink}
import akka.stream.{FlowShape, KillSwitches, Materializer}
import javax.inject._
import play.api._
import play.api.libs.json.{JsObject, JsValue, Json}
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

  case class Line(id: Long, text: String)
  val linesMock = mutable.ListBuffer {
    Line(0, "")
  }

  sealed trait WsAction

  case class FailAction(userToken: String, message: String) extends WsAction
  case class NoneAction() extends WsAction

  sealed trait RoomAction extends WsAction

  sealed trait RoomBroadcastAction extends RoomAction
  case class InsertAction(userToken: String, id: Long, prevId: Long, text: String) extends RoomBroadcastAction
  case class UpdateAction(userToken: String, id: Long, text: String) extends RoomBroadcastAction
  case class DeleteAction(userToken: String, id: Long) extends RoomBroadcastAction

  sealed trait RoomSingleAction extends RoomAction
  case class GetAllAction(userToken: String) extends RoomSingleAction

  def roomProcess(v: JsValue): WsAction = {
    val action = (v \ "action").asOpt[String]
    val userToken = (v \ "userToken").asOpt[String].get // fixme
    val res: WsAction = action match {
      case Some("insert") => {
        val id = (v \ "id").asOpt[Long].getOrElse(-1l)
        val prevId = (v \ "prevId").asOpt[Long].getOrElse(-1l)
        val text = (v \ "text").asOpt[String].getOrElse("")
        val index = linesMock.indexWhere(line => line.id == prevId)
        val newLine = Line(id, text)
        if (index >= 0) {
          if (index < linesMock.size) {
            linesMock.insert(index + 1, newLine)
          } else {
            linesMock += newLine
          }
          InsertAction(userToken, id, prevId, text)
        } else {
          FailAction(userToken, "Error")
        }
      }
      case Some("update") => {
        val id = (v \ "id").asOpt[Long].getOrElse(-1l)
        val text = (v \ "text").asOpt[String].getOrElse("")
        val index = linesMock.indexWhere(line => line.id == id)
        if (index >= 0) {
          linesMock(index) = Line(id, text)
          UpdateAction(userToken, id, text)
        } else {
          FailAction(userToken, "Error")
        }
      }
      case Some("delete") => {
        val id = (v \ "id").asOpt[Long].getOrElse(-1l)
        val index = linesMock.indexWhere(line => line.id == id)
        if (index >= 0) {
          linesMock.remove(index)
          DeleteAction(userToken, id)
        } else {
          FailAction(userToken, "Error")
        }
      }
      case Some("get-all") => {
        GetAllAction(userToken)
      }
      case Some(_) => {
        FailAction(userToken, "Error")
      }
      case None => {
        NoneAction()
      }
    }
    Logger.debug(linesMock.mkString(", "))
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
    case InsertAction(userToken, id, prevId, text) => Json.obj(
      "action" -> "insert",
      "userToken" -> userToken,
      "id" -> id,
      "prevId" -> prevId,
      "text" -> text
    )
    case UpdateAction(userToken, id, text) => Json.obj(
      "action" -> "update",
      "userToken" -> userToken,
      "id" -> id,
      "text" -> text
    )
    case DeleteAction(userToken, id) => Json.obj(
      "action" -> "delete",
      "userToken" -> userToken,
      "id" -> id
    )
  }

  def roomSingleResponse(action: WsAction): JsValue = action match {
    case GetAllAction(userToken: String) => Json.obj(
      "action" -> "get-all",
      "userToken" -> userToken
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

    val inFlow  = ActorFlow.actorRef(out => RequestActor.props(out))

    val outFlow = ActorFlow.actorRef(out => ResponseActor.props(out))

    val graph = GraphDSL.create() { implicit b =>
      val i = b.add(inFlow)
      val o = b.add(outFlow)

      val bcast = b.add(Broadcast[WsAction](2))
      val merge = b.add(Merge[JsValue](2))

      val preProc = b.add(Flow[JsValue].map(roomProcess))
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
