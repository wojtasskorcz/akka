/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey._
import scala.annotation.tailrec
import scala.util.control.NonFatal
import akka.actor.{ ActorLogging, Actor, ActorRef }
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.util.ByteString
import akka.io.SelectionHandler._
import akka.io.Udp._

/**
 * INTERNAL API
 */
private[io] class UdpListener(val udp: UdpExt,
                              channelRegistry: ChannelRegistry,
                              bindCommander: ActorRef,
                              bind: Bind)
  extends Actor with ActorLogging with WithUdpSend with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import udp.bufferPool
  import udp.settings._

  def selector: ActorRef = context.parent

  context.watch(bind.handler) // sign death pact

  val channel = DatagramChannel.open
  channel.configureBlocking(false)

  val localAddress =
    try {
      val socket = channel.socket
      bind.options.foreach(_.beforeBind(channel))
      socket.bind(bind.localAddress)
      val ret = socket.getLocalSocketAddress match {
        case isa: InetSocketAddress ⇒ isa
        case x                      ⇒ throw new IllegalArgumentException(s"bound to unknown SocketAddress [$x]")
      }
      channelRegistry.register(channel, OP_READ)
      log.debug("Successfully bound to [{}]", ret)
      bind.options.foreach(_.afterConnect(channel))
      ret
    } catch {
      case NonFatal(e) ⇒
        bindCommander ! CommandFailed(bind)
        log.debug("Failed to bind UDP channel to endpoint [{}]: {}", bind.localAddress, e)
        context.stop(self)
    }

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      bindCommander ! Bound(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
      context.become(readHandlers(registration) orElse sendHandlers(registration), discardOld = true)
  }

  def readHandlers(registration: ChannelRegistration): Receive = {
    case SuspendReading  ⇒ registration.disableInterest(OP_READ)
    case ResumeReading   ⇒ registration.enableInterest(OP_READ)
    case ChannelReadable ⇒ doReceive(registration, bind.handler)

    case Unbind ⇒
      log.debug("Unbinding endpoint [{}]", bind.localAddress)
      try {
        channel.close()
        sender() ! Unbound
        log.debug("Unbound endpoint [{}], stopping listener", bind.localAddress)
      } finally context.stop(self)
  }

  def doReceive(registration: ChannelRegistration, handler: ActorRef): Unit = {
    @tailrec def innerReceive(readsLeft: Int, buffer: ByteBuffer) {
      buffer.clear()
      buffer.limit(DirectBufferSize)

      channel.receive(buffer) match {
        case sender: InetSocketAddress ⇒
          buffer.flip()
          handler ! Received(ByteString(buffer), sender)
          if (readsLeft > 0) innerReceive(readsLeft - 1, buffer)
        case null ⇒ // null means no data was available
      }
    }

    val buffer = bufferPool.acquire()
    try innerReceive(BatchReceiveLimit, buffer) finally {
      bufferPool.release(buffer)
      registration.enableInterest(OP_READ)
    }
  }

  override def postStop(): Unit = {
    if (channel.isOpen) {
      log.debug("Closing DatagramChannel after being stopped")
      try channel.close()
      catch {
        case NonFatal(e) ⇒ log.debug("Error closing DatagramChannel: {}", e)
      }
    }
  }
}