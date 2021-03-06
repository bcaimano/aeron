/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.driver.buffer.RawLog;
import io.aeron.driver.status.SystemCounters;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.logbuffer.LogBufferUnblocker;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.Position;
import org.agrona.concurrent.status.ReadablePosition;

import static io.aeron.driver.Configuration.PUBLICATION_LINGER_NS;
import static io.aeron.driver.status.SystemCounterDescriptor.UNBLOCKED_PUBLICATIONS;
import static io.aeron.logbuffer.LogBufferDescriptor.*;

/**
 * Encapsulation of a LogBuffer used directly between publishers and subscribers for IPC.
 */
public class IpcPublication implements DriverManagedResource, Subscribable
{
    enum State
    {
        ACTIVE, INACTIVE, LINGER
    }

    private static final ReadablePosition[] EMPTY_POSITIONS = new ReadablePosition[0];

    private final long registrationId;
    private final long unblockTimeoutNs;
    private final int sessionId;
    private final int streamId;
    private final int tripGain;
    private final int termBufferLength;
    private final int termWindowLength;
    private final int positionBitsToShift;
    private final int initialTermId;
    private long tripLimit;
    private long consumerPosition;
    private long lastConsumerPosition;
    private long timeOfLastConsumerPositionUpdateNs;
    private long cleanPosition;
    private long timeOfLastStateChangeNs;
    private int refCount = 0;
    private boolean reachedEndOfLife = false;
    private final boolean isExclusive;
    private State state = State.ACTIVE;
    private final UnsafeBuffer[] termBuffers;
    private ReadablePosition[] subscriberPositions = EMPTY_POSITIONS;
    private final Position publisherLimit;
    private final UnsafeBuffer metaDataBuffer;
    private final RawLog rawLog;
    private final AtomicCounter unblockedPublications;

    public IpcPublication(
        final long registrationId,
        final int sessionId,
        final int streamId,
        final Position publisherLimit,
        final RawLog rawLog,
        final long unblockTimeoutNs,
        final long nowNs,
        final SystemCounters systemCounters,
        final boolean isExclusive)
    {
        this.registrationId = registrationId;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.isExclusive = isExclusive;
        this.termBuffers = rawLog.termBuffers();
        this.initialTermId = initialTermId(rawLog.metaData());

        final int termLength = rawLog.termLength();
        this.termBufferLength = termLength;
        this.positionBitsToShift = Integer.numberOfTrailingZeros(termLength);
        this.termWindowLength = Configuration.ipcPublicationTermWindowLength(termLength);
        this.tripGain = termWindowLength / 8;
        this.publisherLimit = publisherLimit;
        this.rawLog = rawLog;
        this.unblockTimeoutNs = unblockTimeoutNs;
        this.unblockedPublications = systemCounters.get(UNBLOCKED_PUBLICATIONS);
        this.metaDataBuffer = rawLog.metaData();

        consumerPosition = producerPosition();
        lastConsumerPosition = consumerPosition;
        cleanPosition = consumerPosition;
        timeOfLastConsumerPositionUpdateNs = nowNs;
        timeOfLastStateChangeNs = nowNs;
    }

    public int sessionId()
    {
        return sessionId;
    }

    public int streamId()
    {
        return streamId;
    }

    public long registrationId()
    {
        return registrationId;
    }

    public boolean isExclusive()
    {
        return isExclusive;
    }

    public RawLog rawLog()
    {
        return rawLog;
    }

    public int publisherLimitId()
    {
        return publisherLimit.id();
    }

    public void close()
    {
        publisherLimit.close();
        for (final ReadablePosition position : subscriberPositions)
        {
            position.close();
        }

        rawLog.close();
    }

    public void addSubscriber(final ReadablePosition subscriberPosition)
    {
        subscriberPositions = ArrayUtil.add(subscriberPositions, subscriberPosition);
        LogBufferDescriptor.isConnected(metaDataBuffer, true);
    }

    public void removeSubscriber(final ReadablePosition subscriberPosition)
    {
        consumerPosition = Math.max(consumerPosition, subscriberPosition.getVolatile());
        subscriberPositions = ArrayUtil.remove(subscriberPositions, subscriberPosition);
        subscriberPosition.close();

        if (subscriberPositions.length == 0)
        {
            LogBufferDescriptor.isConnected(metaDataBuffer, false);
        }
    }

    int updatePublishersLimit()
    {
        int workCount = 0;
        long minSubscriberPosition = Long.MAX_VALUE;
        long maxSubscriberPosition = consumerPosition;

        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            final long position = subscriberPosition.getVolatile();
            minSubscriberPosition = Math.min(minSubscriberPosition, position);
            maxSubscriberPosition = Math.max(maxSubscriberPosition, position);
        }

        if (subscriberPositions.length > 0)
        {
            if (maxSubscriberPosition != consumerPosition)
            {
                consumerPosition = maxSubscriberPosition;
            }

            final long proposedLimit = minSubscriberPosition + termWindowLength;
            if (proposedLimit > tripLimit)
            {
                publisherLimit.setOrdered(proposedLimit);
                tripLimit = proposedLimit + tripGain;

                cleanBuffer(minSubscriberPosition);
                workCount = 1;
            }
        }
        else if (tripLimit > maxSubscriberPosition)
        {
            tripLimit = maxSubscriberPosition;
            publisherLimit.setOrdered(maxSubscriberPosition);
        }

        return workCount;
    }

    private void cleanBuffer(final long minConsumerPosition)
    {
        final long cleanPosition = this.cleanPosition;
        final UnsafeBuffer dirtyTerm = termBuffers[indexByPosition(cleanPosition, positionBitsToShift)];
        final int bytesForCleaning = (int)(minConsumerPosition - cleanPosition);
        final int bufferCapacity = termBufferLength;
        final int termOffset = (int)cleanPosition & (bufferCapacity - 1);
        final int length = Math.min(bytesForCleaning, bufferCapacity - termOffset);

        if (length > 0)
        {
            dirtyTerm.setMemory(termOffset, length, (byte)0);
            this.cleanPosition = cleanPosition + length;
        }
    }

    public long joinPosition()
    {
        return consumerPosition;
    }

    public long producerPosition()
    {
        final long rawTail = rawTailVolatile(metaDataBuffer);
        final int termOffset = termOffset(rawTail, termBufferLength);

        return computePosition(termId(rawTail), termOffset, positionBitsToShift, initialTermId);
    }

    public void onTimeEvent(final long timeNs, final long timeMs, final DriverConductor conductor)
    {
        checkForBlockedPublisher(timeNs);

        switch (state)
        {
            case INACTIVE:
                if (isDrained())
                {
                    state = State.LINGER;
                    timeOfLastStateChangeNs = timeNs;
                    conductor.transitionToLinger(this);
                }
                break;

            case LINGER:
                if (timeNs > (timeOfLastStateChangeNs + PUBLICATION_LINGER_NS))
                {
                    reachedEndOfLife = true;
                    conductor.cleanupIpcPublication(this);
                }
                break;
        }
    }

    public boolean hasReachedEndOfLife()
    {
        return reachedEndOfLife;
    }

    public void timeOfLastStateChange(final long timeNs)
    {
        timeOfLastStateChangeNs = timeNs;
    }

    public long timeOfLastStateChange()
    {
        return timeOfLastStateChangeNs;
    }

    public void delete()
    {
        close();
    }

    public int incRef()
    {
        return ++refCount;
    }

    public int decRef()
    {
        final int count = --refCount;

        if (0 == count)
        {
            state = State.INACTIVE;
            LogBufferDescriptor.endOfStreamPosition(metaDataBuffer, producerPosition());
        }

        return count;
    }

    long consumerPosition()
    {
        return consumerPosition;
    }

    State state()
    {
        return state;
    }

    private boolean isDrained()
    {
        final long producerPosition = producerPosition();

        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            if (subscriberPosition.getVolatile() < producerPosition)
            {
                return false;
            }
        }

        return true;
    }

    private void checkForBlockedPublisher(final long timeNs)
    {
        final long consumerPosition = this.consumerPosition;
        if (consumerPosition == lastConsumerPosition && producerPosition() > consumerPosition)
        {
            if (timeNs > (timeOfLastConsumerPositionUpdateNs + unblockTimeoutNs))
            {
                if (LogBufferUnblocker.unblock(termBuffers, metaDataBuffer, consumerPosition))
                {
                    unblockedPublications.orderedIncrement();
                }
            }
        }
        else
        {
            timeOfLastConsumerPositionUpdateNs = timeNs;
            lastConsumerPosition = consumerPosition;
        }
    }
}
