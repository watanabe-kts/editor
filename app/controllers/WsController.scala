package controllers

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.{BroadcastHub, Flow, GraphDSL, Keep, MergeHub, Sink}
import akka.stream.{FlowShape, KillSwitches, Materializer}
import javax.inject._
import play.api._
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.streams.ActorFlow
import play.api.mvc._

import scala.collection.mutable
import scala.concurrent.duration._

@Singleton
class WsController @Inject()(implicit system: ActorSystem, materializer: Materializer, cc: ControllerComponents) extends AbstractController(cc) {
  import GraphDSL.Implicits._

  case class Line(id: Long, text: String)
  val linesMock = mutable.ListBuffer {
    Line(0, "")
  }

  def process(v: JsValue): JsValue = {
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
      }
      case Some("update") => {

      }
      case Some("delete") => {

      }
      case Some(_) => {

      }
      case None => {

      }
    }
    v
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
      .map(process)

    val outFlow = Flow[JsValue].filter(v => true)
      .via(ActorFlow.actorRef(out => ResponseActor.props(out)))

    /*
    val inGraph = GraphDSL.create() { implicit b =>
      val i = b.add(inFlow)
      val o = b.add(outFlow)

      val f = Flow[JsValue]
          .map(process)

      i ~> f

      FlowShape(i.in, f.out)
    }
    Flow.fromGraph(inGraph)
    */

    inFlow.viaMat(bus)(Keep.right)
      .map({x => Logger.debug("bcast: " + x); x})
      .viaMat(outFlow)(Keep.right)
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
