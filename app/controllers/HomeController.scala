package controllers

import javax.inject._
import play.api._
import play.api.mvc._

/**
 * ホームページ表示
 */
@Singleton
class HomeController @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  def index() = Action { implicit request: Request[AnyContent] =>
    val name = request.session.get("name")
    Ok(views.html.index(name))
  }
}
