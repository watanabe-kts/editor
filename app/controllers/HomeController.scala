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

  def docList() = Action { implicit request =>
    val token = request.session.get("token")
    val account = token.flatMap(Account.getSession).map(_.account)

    account match {
      case Some(_) => Ok(views.html.doclist(account.map(_.name)))
      case None => Redirect(routes.HomeController.index())
    }
  }

  def editor() = Action { implicit request =>
    val token = request.session.get("token")
    val account = token.flatMap(Account.getSession).map(_.account)
    account match {
      case Some(_) => Ok(views.html.editor(account.map(_.name)))
      case None => Redirect(routes.HomeController.index())
    }
  }

  def index() = Action { implicit request: Request[AnyContent] =>
    val token = request.session.get("token")
    val account = token.flatMap(Account.getSession).map(_.account)
    account match {
      case Some(_) => Redirect(routes.HomeController.docList())
      case None => Ok (views.html.index(account.map(_.name)))
    }
  }
}
