/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.uring;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.channel.unix.FileDescriptor;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.channel.unix.Errors.*;

final class IOUringEventLoop extends SingleThreadEventLoop implements
        IOUringCompletionQueue.IOUringCompletionQueueCallback {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(IOUringEventLoop.class);

    //Todo set config ring buffer size
    private final int ringSize = 32;

    //just temporary -> Todo use ErrorsStaticallyReferencedJniMethods like in Epoll
    private final int SOCKET_ERROR_EPIPE = -32;
    private static long ETIME = -62;

    // events should be unique to identify which event type that was
    private long eventIdCounter;
    private final IntObjectMap<AbstractIOUringChannel> channels = new IntObjectHashMap<AbstractIOUringChannel>(4096);
    private final RingBuffer ringBuffer;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);
    private final FileDescriptor eventfd;

    private long prevDeadlineNanos = NONE;
    private boolean pendingWakeup;

    IOUringEventLoop(final EventLoopGroup parent, final Executor executor, final boolean addTaskWakesUp) {
        super(parent, executor, addTaskWakesUp);

        ringBuffer = Native.createRingBuffer(ringSize);
        eventfd = Native.newEventFd();
        logger.trace("New EventLoop: {}", this.toString());
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    public void add(AbstractIOUringChannel ch) {
        logger.trace("Add Channel: {} ", ch.socket.intValue());
        int fd = ch.socket.intValue();

        channels.put(fd, ch);
    }

    public void remove(AbstractIOUringChannel ch) {
        logger.trace("Remove Channel: {}", ch.socket.intValue());
        int fd = ch.socket.intValue();

        AbstractIOUringChannel old = channels.remove(fd);
        if (old != null && old != ch) {
            // The Channel mapping was already replaced due FD reuse, put back the stored Channel.
            channels.put(fd, old);

            // If we found another Channel in the map that is mapped to the same FD the given Channel MUST be closed.
            assert !ch.isOpen();
        }
    }

    private void closeAll() {
        logger.trace("CloseAll IOUringEvenloop");
        // Using the intermediate collection to prevent ConcurrentModificationException.
        // In the `close()` method, the channel is deleted from `channels` map.
        AbstractIOUringChannel[] localChannels = channels.values().toArray(new AbstractIOUringChannel[0]);

        for (AbstractIOUringChannel ch : localChannels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    @Override
    protected void run() {
        final IOUringCompletionQueue completionQueue = ringBuffer.getIoUringCompletionQueue();
        final IOUringSubmissionQueue submissionQueue = ringBuffer.getIoUringSubmissionQueue();

        // Lets add the eventfd related events before starting to do any real work.
        submissionQueue.addPollLink(eventfd.intValue());
        submissionQueue.submit();

        for (;;) {
            logger.trace("Run IOUringEventLoop {}", this.toString());
            long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
            if (curDeadlineNanos == -1L) {
                curDeadlineNanos = NONE; // nothing on the calendar
            }
            nextWakeupNanos.set(curDeadlineNanos);

            // Only submit a timeout if there are no tasks to process and do a blocking operation
            // on the completionQueue.
            if (!hasTasks()) {
                try {
                    if (curDeadlineNanos != prevDeadlineNanos) {
                        prevDeadlineNanos = curDeadlineNanos;
                        submissionQueue.addTimeout(deadlineToDelayNanos(curDeadlineNanos));
                        submissionQueue.submit();
                    }

                    // Check there were any completion events to process
                    if (completionQueue.process(this) == -1) {
                        // Block if there is nothing to process after this try again to call process(....)
                        logger.trace("ioUringWaitCqe {}", this.toString());
                        completionQueue.ioUringWaitCqe();
                    }
                } finally {
                    if (nextWakeupNanos.get() == AWAKE || nextWakeupNanos.getAndSet(AWAKE) == AWAKE) {
                        pendingWakeup = true;
                    }
                }
            }

            completionQueue.process(this);

            if (hasTasks()) {
                runAllTasks();
            }

            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        break;
                    }
                }
            } catch (Throwable t) {
                logger.info("Exception error: {}", t);
            }
        }
    }

    @Override
    public boolean handle(int fd, int res, long flags, int op, int pollMask) {
        IOUringSubmissionQueue submissionQueue = ringBuffer.getIoUringSubmissionQueue();
        switch (op) {
            case IOUring.OP_ACCEPT:
                AbstractIOUringServerChannel acceptChannel = (AbstractIOUringServerChannel) channels.get(fd);
                if (acceptChannel == null) {
                    break;
                }
                logger.trace("EventLoop Accept filedescriptor: {}", res);
                acceptChannel.setUringInReadyPending(false);
                if (res != -1 && res != ERRNO_EAGAIN_NEGATIVE &&
                        res != ERRNO_EWOULDBLOCK_NEGATIVE) {
                    logger.trace("server filedescriptor Fd: {}", fd);
                    acceptChannel.acceptComplete(res);
                    pollRdHup(res);
                }
                break;
            case IOUring.OP_READ:
                AbstractIOUringChannel readChannel = channels.get(fd);
                if (readChannel == null) {
                    break;
                }
                readChannel.readComplete(res);
                break;
            case IOUring.OP_WRITE:
                AbstractIOUringChannel writeChannel = channels.get(fd);
                if (writeChannel == null) {
                    break;
                }
                //localFlushAmount -> res
                logger.trace("EventLoop Write Res: {}", res);
                logger.trace("EventLoop Fd: {}", fd);

                if (res == SOCKET_ERROR_EPIPE) {
                    writeChannel.shutdownInput(false);
                } else {
                    writeChannel.writeComplete(res);
                }
                break;
            case IOUring.IO_TIMEOUT:
                if (res == ETIME) {
                    prevDeadlineNanos = NONE;
                }

                break;
            case IOUring.IO_POLL:
                if (eventfd.intValue() == fd) {
                    pendingWakeup = false;
                    // We need to consume the data as otherwise we would see another event in the completionQueue without
                    // an extra eventfd_write(....)
                    Native.eventFdRead(eventfd.intValue());
                    submissionQueue.addPollLink(eventfd.intValue());
                    // Submit so its picked up
                    submissionQueue.submit();
                } else {
                    if (pollMask == IOUring.POLLMASK_RDHUP) {
                        AbstractIOUringChannel channel = channels.get(fd);
                        if (channel != null && !channel.isActive()) {
                            channel.shutdownInput(true);
                        }
                    } else {
                        //Todo error handling error
                        logger.trace("POLL_LINK Res: {}", res);
                        break;
                    }
                }

                break;
        }
        return true;
    }

    @Override
    protected void cleanup() {
        try {
            eventfd.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        ringBuffer.close();
    }

    public RingBuffer getRingBuffer() {
        return ringBuffer;
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            // write to the evfd which will then wake-up epoll_wait(...)
            Native.eventFdWrite(eventfd.intValue(), 1L);
        }
    }

   //to be notified when the filedesciptor is closed
   private void pollRdHup(int fd) {
        //all childChannels should poll POLLRDHUP
        ringBuffer.getIoUringSubmissionQueue().addPollRdHup(fd);
        ringBuffer.getIoUringSubmissionQueue().submit();
    }
}
