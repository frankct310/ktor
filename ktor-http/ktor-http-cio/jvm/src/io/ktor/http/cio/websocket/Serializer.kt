/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.http.cio.websocket

import io.ktor.util.*
import java.nio.*
import java.util.concurrent.*

@Suppress("KDocMissingDocumentation")
@WebSocketInternalAPI
public class Serializer {
    private val messages = ArrayBlockingQueue<Frame>(1024)

    private var frameBody: ByteBuffer? = null
    private var maskBuffer: ByteBuffer? = null

    public var masking: Boolean = false

    public val hasOutstandingBytes: Boolean
        get() = messages.isNotEmpty() || frameBody != null

    public val remainingCapacity: Int get() = messages.remainingCapacity()

    public fun enqueue(f: Frame) {
        messages.put(f)
    }

    public fun serialize(buffer: ByteBuffer) {
        while (writeCurrentPayload(buffer)) {
            val frame = messages.peek() ?: break
            val mask = masking
            setMaskBuffer(mask)

            val headerSize = estimateFrameHeaderSize(frame, mask)
            if (buffer.remaining() < headerSize) {
                break
            }

            serializeHeader(frame, buffer, mask)
            messages.remove()
            frameBody = frame.buffer.maskedIfNeeded()
        }
    }

    private fun serializeHeader(frame: Frame, buffer: ByteBuffer, mask: Boolean) {
        val size = frame.buffer.remaining()
        val length1 = when {
            size < 126 -> size
            size <= 0xffff -> 126
            else -> 127
        }

        buffer.put(
            (frame.fin.flagAt(7) or frame.frameType.opcode).toByte()
        )
        buffer.put(
            (mask.flagAt(7) or length1).toByte()
        )

        if (length1 == 126) {
            buffer.putShort(frame.buffer.remaining().toShort())
        } else if (length1 == 127) {
            buffer.putLong(frame.buffer.remaining().toLong())
        }

        maskBuffer?.duplicate()?.moveTo(buffer)
    }

    private fun estimateFrameHeaderSize(f: Frame, mask: Boolean): Int {
        val size = f.buffer.remaining()
        return when {
            size < 126 -> 2
            size <= Short.MAX_VALUE -> 2 + 2
            else -> 2 + 8
        } + maskSize(mask)
    }


    private fun writeCurrentPayload(buffer: ByteBuffer): Boolean {
        val frame = frameBody ?: return true
        frame.moveTo(buffer)
        if (!frame.hasRemaining()) {
            frameBody = null
            return true
        }

        return false
    }

    private fun maskSize(mask: Boolean) = if (mask) 4 else 0

    private fun ByteBuffer.maskedIfNeeded() = maskBuffer?.let { mask -> copy().apply { xor(mask) } } ?: this

    private fun setMaskBuffer(mask: Boolean) {
        if (mask) {
            maskBuffer = ByteBuffer.allocate(4).apply {
                putInt(generateNonce().hashCode())
                clear()
            }
        } else {
            maskBuffer = null
        }
    }
}
