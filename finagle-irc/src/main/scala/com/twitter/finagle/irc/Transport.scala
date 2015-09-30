package com.twitter.finagle.irc

import com.twitter.finagle.netty3.ChannelBufferBuf
import com.twitter.finagle.transport.{Transport => FTransport}
import com.twitter.io.Buf
import com.twitter.util.{Future, Time}
import java.nio.charset.Charset
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}

case class Transport(
  trans: FTransport[ChannelBuffer, ChannelBuffer],
  decoder: (List[String] => Option[Message]) = DefaultIrcDecoder
) extends FTransport[Message, Message] {
  private[this] val Utf8 = Charset.forName("UTF-8")
  private[this] val Delimiter = "\r\n"
  private[this] val ServerUser = """([^!]+)!?([^@]*)?@?(.+)?""".r
  @volatile private[this] var buf: Buf = Buf.Empty

  def write(msg: Message): Future[Unit] =
    trans.write(ChannelBuffers.wrappedBuffer((msg.encode + Delimiter).getBytes(Utf8)))

  def read(): Future[Message] = buf match {
    case Buf.Utf8(str) if str.contains(Delimiter) =>
      val msgs = str.split(Delimiter)
      buf = msgs.drop(1).mkString(Delimiter) match {
        case "" =>
          Buf.Empty
        case newStr =>
          val post = if (str.endsWith(Delimiter)) Delimiter else ""
          Buf.Utf8(newStr + post)
      }

      Future(decode(msgs(0)))
    case _ =>
      trans.read() flatMap { m =>
        buf = buf.concat(ChannelBufferBuf(m))
        read()
      }
  }

  def status = trans.status
  val onClose = trans.onClose
  def localAddress = trans.localAddress
  def remoteAddress = trans.remoteAddress
  def close(deadline: Time) = trans.close(deadline)
  def peerCertificate: Option[java.security.cert.Certificate] = None

  private[this] def decode(cmdStr: String): Message = {
    // TODO: println
    println("< " + cmdStr)

    def decodeCmd(cmdStr: String) = {
      val cmd :: tail = cmdStr.split(":", 2).toList: List[String]
      val tkns: List[String] = cmd.split(" ").toList ++ tail
      decoder(tkns) getOrElse { UnknownCmd(tkns.head, tkns.tail.mkString(" ")) }
    }

    if (!cmdStr.startsWith(":")) decodeCmd(cmdStr) else {
      val head :: cmd :: Nil = cmdStr.split(" ", 1).toList
      val ServerUser(nick, name, host) = head
      ServerMessage(nick, Option(name), Option(host), decodeCmd(cmd))
    }
  }
}
