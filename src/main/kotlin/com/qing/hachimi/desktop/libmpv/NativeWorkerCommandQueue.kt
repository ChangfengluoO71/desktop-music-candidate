package com.qing.hachimi.desktop.libmpv

import java.util.concurrent.LinkedBlockingQueue

/** A command mailbox whose shutdown and final drain are atomic with command submission. */
internal class NativeWorkerCommandQueue<T> {
    private val monitor = Any()
    private val queue = LinkedBlockingQueue<T>()
    private var stopCause: Throwable? = null

    fun offer(command: T, reject: (Throwable) -> Unit): Boolean = synchronized(monitor) {
        val stoppedWith = stopCause
        if (stoppedWith != null) {
            reject(stoppedWith)
            false
        } else {
            queue.offer(command)
        }
    }

    fun poll(): T? = queue.poll()

    fun stop(failure: Throwable, reject: (T, Throwable) -> Unit) = synchronized(monitor) {
        val terminalCause = stopCause ?: failure.also { stopCause = it }
        while (true) {
            val command = queue.poll() ?: break
            reject(command, terminalCause)
        }
    }
}
