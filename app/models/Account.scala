package models

import play.api.Logger
import play.api.db.Database
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

import scala.collection.mutable
import scala.util.{Random, Try}

object Account {
  val bcrypt = new BCryptPasswordEncoder()
  def toDigest(pw: String) = bcrypt.encode(pw)
  def auth(pw: String, digest: String) = bcrypt.matches(pw, digest)

  def create(account: Account)(implicit db: Database) = {
    val conn = db.getConnection()
    try {
      val stmt = conn.createStatement
      val digest = toDigest(account.password)
      try {
        val sql = s"insert into account (name, password_digest) values ('${account.name}', '${digest}')"
        Logger.debug(s"Exec SQL: ${sql}")
        Try(stmt.executeUpdate(sql))
          .fold(
            _ => Left("Create account failed"),
            _ => Right(())
          )
      }
    } finally {
      conn.close()
    }
  }

  def login(account: Account)(implicit db: Database) = {
    val conn = db.getConnection()
    try {
      val stmt = conn.createStatement()
      try {
        val sql = s"select id, password_digest from account where name = '${account.name}'"
        Logger.debug(s"Exec SQL: ${sql}")
        Try(stmt.executeQuery(sql))
          .fold(
            e => {
              Logger.debug(e.toString)
              Left("Authentication failed")
            },
            res => {
              res.first
              if (!res.isAfterLast) {
                if (auth(account.password, res.getString("password_digest")))
                  Right(res.getInt("id"))
                else
                  Left("Wrong password")
              } else {
                Left("Wrong account name")
              }
            }
          )
      }
    } finally {
      conn.close()
    }
  }

  val kvsMock = mutable.Map.empty[String, Session]
  val random = Random

  def createSession(account: Account) = {
    val key = random.nextLong().toString
    val session = Session(key, account)
    kvsMock += (key -> session)
    session
  }

  def deleteSession(key: String) = {
    kvsMock -= key
  }

  def getSession(key: String) = {
    kvsMock get key
  }
}

case class Session(token: String, account: Account)

case class Account (name: String, password: String) {

}

