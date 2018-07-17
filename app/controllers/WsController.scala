package controllers

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.{Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Merge, MergeHub, Sink}
import akka.stream.{FlowShape, KillSwitches, Materializer}
import javax.inject._
import play.api._
import play.api.libs.json.{JsObject, JsValue}
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

  def roomProcess(v: JsValue): JsValue = {
    val action = (v \ "action").asOpt[String]
    action match {
      case Some("insert") => {
        val id = (v \ "id").asOpt[Long].getOrElse(-1l)
        val prevId = (v \ "prevId").asOpt[Long].getOrElse(-1l)
        val text = (v \ "text").asOpt[String].getOrElse("")
        val index = linesMock.indexWhere(line => line.id == prevId)
        val newLine = Line(id, text)
        if (index < linesMock.size) {
          linesMock.insert(index + 1, newLine)
        } else {
          linesMock += newLine
        }
        v
      }
      case Some("update") => {
        // Todo
        val id = (v \ "id").asOpt[Long].getOrElse(-1l)
        val text = (v \ "text").asOpt[String].getOrElse("")

        v
      }
      case Some("delete") => {
        // Todo
        v
      }
      case Some(_) => {
        // Todo
        v
      }
      case None => {
        // Todo
        v
      }
    }
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
      .map(roomProcess)

    val outFlow = Flow[JsValue].filter(v => true)
      .via(ActorFlow.actorRef(out => ResponseActor.props(out)))

    val graph = GraphDSL.create() { implicit b =>
      val i = b.add(inFlow)
      val o = b.add(outFlow)

      val bcast = b.add(Broadcast[JsValue](2))
      val merge = b.add(Merge[JsValue](2))

      val roomBus = b.add(bus)

      val core = b.add(Flow[JsValue].filter(_ => false))

      i ~> bcast ~> roomBus ~> merge ~> o
           bcast ~> core    ~> merge

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
