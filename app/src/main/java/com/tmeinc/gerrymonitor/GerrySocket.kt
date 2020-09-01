package com.tmeinc.gerrymonitor

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GerrySocket : Closeable {
    private val socket = SocketChannel.open()
    private val ackQueue =
        LinkedBlockingQueue<GerryMsg>()           // for ack of client commands

    val isConnected
        get() = socket.isConnected

    val isOpen
        get() = socket.isOpen

    override fun close() {
        socket.close()
        ackQueue.clear()
    }

    var commandListener: (GerryMsg?) -> Boolean = { false }

    fun connect(hostname: String, port: Int): Boolean {
        try {
            socket.connect(InetSocketAddress(hostname, port))
        } catch (e: Exception) {
            close()
        }
        if (isConnected) {
            ackQueue.clear()
            // receiver thread
            executorService.submit {
                while (isConnected) {
                    try {
                        val msg = recvGerryMsg()
                        if (msg != null) {
                            if (msg.ack == GerryMsg.ACK_NONE) {
                                if (!commandListener(msg)) {       // if success, response msg is set
                                    msg.ack = GerryMsg.ACK_FAIL
                                    msg.reason = GerryMsg.REASON_NONE
                                    msg.clearData()
                                }
                                sendGerryMsg(msg)
                            } else {
                                ackQueue.offer(msg)
                            }
                        } else {
                            close()
                            commandListener(null)
                            break
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
                close()
            }
            return true
        }
        close()
        return false
    }

    fun sendGerryMsg(msg: GerryMsg) {
        if (!socket.isConnected)
            return

        try {
            // calc crc
            msg.crc()
            msg.mssMsg.rewind()
            msg.xData.rewind()
            socket.write(arrayOf(msg.mssMsg, msg.xData))
        } catch (e: IOException) {
            socket.close()
        } finally {
            msg.mssMsg.rewind()
            msg.xData.rewind()
        }

    }

    // return null if eof or error
    private fun recvGerryMsg(): GerryMsg? {
        if (!socket.isConnected)
            return null

        try {
            val msg = GerryMsg()
            var r: Int
            while (msg.mssMsg.hasRemaining()) {
                r = socket.read(msg.mssMsg)
                if (r < 0) {
                    return null     // eof before read
                } else if (r == 0) {
                    Thread.sleep(100)
                }
            }
            msg.mssMsg.rewind()
            if (msg.isValid && msg.extSize >= 0 && msg.extSize <= GerryMsg.MAX_XDATA) {
                if (msg.extSize > 0) {
                    msg.xData = ByteBuffer.allocate(msg.extSize)
                    while (msg.xData.hasRemaining()) {
                        r = socket.read(msg.xData)
                        if (r < 0) {
                            return null     // eof before read
                        } else if (r == 0) {
                            Thread.sleep(100)
                        }
                    }
                    msg.xData.rewind()
                }
                return msg
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    // wait for gerry ACK
    fun gerryAck(cmd: Int, timeout: Int = 30000): GerryMsg? {
        while (socket.isConnected) {
            try {
                val ack = ackQueue.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
                if (ack != null) {
                    if (ack.command == cmd) {      // command matched
                        if (ack.ack == GerryMsg.ACK_SUCCESS) {
                            return ack
                        } else
                            break
                    }
                } else {
                    break
                }
            } catch (e: InterruptedException) {
                // keep interrupted state
                Thread.currentThread().interrupt()
                break
            }
        }
        return null
    }

    fun gerryCmd(cmd: Int, xmlStr: String? = null): GerryMsg? {
        if (socket.isConnected) {
            val msg = GerryMsg(cmd, xmlStr)
            ackQueue.clear()        // clear ack queue before new command
            sendGerryMsg(msg)
            return gerryAck(cmd)
        }
        return null
    }

    fun gerryCmd(cmd: Int, xmlData: Map<*, *>): GerryMsg? {
        return gerryCmd(cmd, xmlData.toXml())
    }
}