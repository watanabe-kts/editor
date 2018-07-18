package controllers

import javax.inject._
import models.Account
import models.Account._
import play.api._
import play.api.mvc._

/**
 * ホームページ表示
 */
@Singleton
class HomeController @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  def index() = Action { implicit request: Request[AnyContent] =>
    val token = request.session.get("token")
    val account = token.flatMap(Account.getSession).map(_.account)
    Ok(views.html.index(account.map(_.name)))
  }
}
