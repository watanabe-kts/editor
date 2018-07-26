package controllers

import javax.inject._
import models.Account
import models.Account._
import play.api._
import play.api.data._
import play.api.data.Forms._
import play.api.mvc._
import play.api.db._


case class LoginData(name: String, password: String)
case class SignupData(name: String, password: String)

/**
  * サインイン、ログイン、ログアウト
  * @param db
  * @param messagesAction
  * @param cc
  */
@Singleton
class AccountController @Inject()(implicit db: Database, messagesAction: MessagesActionBuilder, cc: ControllerComponents) extends AbstractController(cc) {
  val NAME_LENGTH_MIN = 4
  val NAME_LENGTH_MAX = 30
  val PASSWORD_LENGTH_MIN = 4
  val PASSWORD_LENGTH_MAX = 30

  val loginForm = Form(
    mapping(
      "name" -> nonEmptyText(NAME_LENGTH_MIN, NAME_LENGTH_MAX),
      "password" -> nonEmptyText(PASSWORD_LENGTH_MIN, PASSWORD_LENGTH_MAX)
    )(LoginData.apply)(LoginData.unapply)
  )

  val signupForm = Form(
    mapping(
      "name" -> nonEmptyText(NAME_LENGTH_MIN, NAME_LENGTH_MAX),
      "password" -> nonEmptyText(PASSWORD_LENGTH_MIN, PASSWORD_LENGTH_MAX)
    )(SignupData.apply)(SignupData.unapply)
  )

  def loginPage() = messagesAction { implicit request =>
    val msg = request.flash.get("success").getOrElse("")
    Ok(views.html.login(loginForm, msg))
  }

  def loginPost() = messagesAction { implicit request =>
    loginForm.bindFromRequest.fold(
      formWithErrors => {
        BadRequest(views.html.login(formWithErrors, "Error"))
      },
      loginData => {
        val account = Account(loginData.name, loginData.password)
        val res = Account.login(account)
        res.fold(
          msg => BadRequest(views.html.login(loginForm, msg)),
          _ => {
            val session = Account.createSession(account)
            Redirect(routes.HomeController.docList()).flashing("success" -> "Logging in succeeded!")
              .withSession("token" -> session.token)
          }
        )
      }
    )
  }

  def logout() = messagesAction { implicit request =>
    val token = request.session.get("token")
    Logger.debug("Logged out: " + token)
    token.foreach{ t =>
      Account.deleteSession(t)
    }
    Redirect(routes.HomeController.index())
      .withNewSession
      .flashing("success" -> "Logout succeed!")
  }

  def signupPage() = messagesAction { implicit request =>
    Ok(views.html.signup(signupForm, ""))
  }

  def signupPost() = messagesAction { implicit request =>
    signupForm.bindFromRequest.fold(
      formWithErrors => {
        BadRequest(views.html.signup(formWithErrors, "Error"))
      },
      signupData => {
        val newAccount = Account(signupData.name, signupData.password)
        val res = Account.create(newAccount)
        res.fold(
          msg => BadRequest(views.html.signup(signupForm, msg)),
          _ => Redirect(routes.AccountController.loginPage()).flashing("success" -> "Signing up succeeded!")
        )
      }
    )
  }

  def dbTest() = Action { implicit request =>
    var outString = "Number is "
    val conn = db.getConnection()
    try {
      val stmt = conn.createStatement
      val rs = stmt.executeQuery("SELECT 9 as testkey ")
      while (rs.next()) {
        outString += rs.getString("testkey")
      }
    } finally {
      conn.close()
    }
    Ok(outString)
  }
}
