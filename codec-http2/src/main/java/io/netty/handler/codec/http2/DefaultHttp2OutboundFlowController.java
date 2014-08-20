/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;

import static io.netty.handler.codec.http2.Http2CodecUtil.*;
import static io.netty.handler.codec.http2.Http2Error.*;
import static io.netty.handler.codec.http2.Http2Exception.*;
import static java.lang.Math.*;

/**
 * Basic implementation of {@link Http2OutboundFlowController}.
 */
public class DefaultHttp2OutboundFlowController implements Http2OutboundFlowController {

    /**
     * A comparators that sorts priority nodes in ascending order by the amount of priority data
     * available for its subtree.
     */
    private static final Comparator<Http2Stream> DATA_WEIGHT = new Comparator<Http2Stream>() {
        private static final int MAX_DATA_THRESHOLD = Integer.MAX_VALUE / 256;

        @Override
        public int compare(Http2Stream o1, Http2Stream o2) {
            int o1Data = state(o1).priorityBytes();
            int o2Data = state(o2).priorityBytes();
            if (o1Data > MAX_DATA_THRESHOLD || o2Data > MAX_DATA_THRESHOLD) {
                // Corner case to make sure we don't overflow an integer with
                // the multiply.
                return o1Data - o2Data;
            }

            // Scale the data by the weight.
            return o1Data * o1.weight() - o2Data * o2.weight();
        }
    };

    private final Http2Connection connection;
    private int initialWindowSize = DEFAULT_WINDOW_SIZE;

    public DefaultHttp2OutboundFlowController(Http2Connection connection) {
        if (connection == null) {
            throw new NullPointerException("connection");
        }
        this.connection = connection;

        // Add a flow state for the connection.
        connection.connectionStream().outboundFlow(
                new OutboundFlowState(connection.connectionStream()));

        // Register for notification of new streams.
        connection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void streamAdded(Http2Stream stream) {
                // Just add a new flow state to the stream.
                stream.outboundFlow(new OutboundFlowState(stream));
            }

            @Override
            public void streamHalfClosed(Http2Stream stream) {
                if (!stream.localSideOpen()) {
                    // Any pending frames can never be written, clear and
                    // write errors for any pending frames.
                    state(stream).clear();
                }
            }

            @Override
            public void streamInactive(Http2Stream stream) {
                // Any pending frames can never be written, clear and
                // write errors for any pending frames.
                state(stream).clear();
            }

            @Override
            public void streamPriorityChanged(Http2Stream stream, Http2Stream previousParent) {
                if (stream.parent() != previousParent) {
                    // The parent changed, move the priority bytes to the new parent.
                    int priorityBytes = state(stream).priorityBytes();
                    state(previousParent).incrementPriorityBytes(-priorityBytes);
                    state(stream.parent()).incrementPriorityBytes(priorityBytes);
                }
            }

            @Override
            public void streamPrioritySubtreeChanged(Http2Stream stream, Http2Stream subtreeRoot) {
                // Reset the priority bytes for the entire subtree.
                resetSubtree(subtreeRoot);
            }
        });
    }

    @Override
    public void initialOutboundWindowSize(int newWindowSize) throws Http2Exception {
        int delta = newWindowSize - initialWindowSize;
        initialWindowSize = newWindowSize;
        connectionState().incrementStreamWindow(delta);
        for (Http2Stream stream : connection.activeStreams()) {
            // Verify that the maximum value is not exceeded by this change.
            OutboundFlowState state = state(stream);
            state.incrementStreamWindow(delta);
        }

        if (delta > 0) {
            // The window size increased, send any pending frames for all streams.
            writePendingBytes();
        }
    }

    @Override
    public int initialOutboundWindowSize() {
        return initialWindowSize;
    }

    @Override
    public void updateOutboundWindowSize(int streamId, int delta) throws Http2Exception {
        if (streamId == CONNECTION_STREAM_ID) {
            // Update the connection window and write any pending frames for all streams.
            connectionState().incrementStreamWindow(delta);
            writePendingBytes();
        } else {
            // Update the stream window and write any pending frames for the stream.
            OutboundFlowState state = stateOrFail(streamId);
            state.incrementStreamWindow(delta);
            state.writeBytes(state.writableWindow());
        }
    }

    @Override
    public void sendFlowControlled(int streamId, ByteBuf data, int padding, boolean endStream,
            FrameWriter frameWriter) throws Http2Exception {
        OutboundFlowState state = stateOrFail(streamId);
        OutboundFlowState.Frame frame =
                state.newFrame(data, padding, endStream, frameWriter);

        // Limit the window for this write by the maximum frame size.
        int window = state.writableWindow();

        int dataLength = data.readableBytes();
        if (window >= dataLength) {
            // Window size is large enough to send entire data frame
            frame.write();
            return;
        }

        // Enqueue the frame to be written when the window size permits.
        frame.enqueue();

        if (window <= 0) {
            // Stream is stalled, don't send anything now.
            return;
        }

        // Create and send a partial frame up to the window size.
        frame.split(window).write();
    }

    private static OutboundFlowState state(Http2Stream stream) {
        return (OutboundFlowState) stream.outboundFlow();
    }

    private OutboundFlowState connectionState() {
        return state(connection.connectionStream());
    }

    private OutboundFlowState state(int streamId) {
        return state(connection.stream(streamId));
    }

    /**
     * Attempts to get the {@link OutboundFlowState} for the given stream. If not available, raises
     * a {@code PROTOCOL_ERROR}.
     */
    private OutboundFlowState stateOrFail(int streamId) throws Http2Exception {
        OutboundFlowState state = state(streamId);
        if (state == null) {
            throw protocolError("Missing flow control window for stream: %d", streamId);
        }
        return state;
    }

    /**
     * Returns the flow control window for the entire connection.
     */
    private int connectionWindow() {
        return connectionState().window();
    }

    /**
     * Resets the priority bytes for the given subtree following a restructuring of the priority
     * tree.
     */
    private void resetSubtree(Http2Stream subtree) {
        // Reset the state priority bytes for this node to its pending bytes and propagate the
        // delta required for this change up the tree. It's important to note that the total number
        // of priority bytes for this subtree hasn't changed. As we traverse the subtree we will
        // subtract off values from the parent of this tree, but we'll add them back later as we
        // traverse the rest of the subtree.
        OutboundFlowState state = state(subtree);
        int delta = state.pendingBytes - state.priorityBytes;
        state.incrementPriorityBytes(delta);

        // Now recurse this operation for each child.
        for (Http2Stream child : subtree.children()) {
            resetSubtree(child);
        }
    }

    /**
     * Writes as many pending bytes as possible, according to stream priority.
     */
    private void writePendingBytes() throws Http2Exception {

        // Recursively write as many of the total writable bytes as possible.
        Http2Stream connectionStream = connection.connectionStream();
        int totalAllowance = state(connectionStream).priorityBytes();
        writeAllowedBytes(connectionStream, totalAllowance);
    }

    /**
     * Recursively traverses the priority tree rooted at the given node. Attempts to write the
     * allowed bytes for the streams in this sub tree based on their weighted priorities.
     *
     * @param allowance an allowed number of bytes that may be written to the streams in this subtree
     */
    private void writeAllowedBytes(Http2Stream stream, int allowance) throws Http2Exception {
        // Write the allowed bytes for this node. If not all of the allowance was used,
        // restore what's left so that it can be propagated to future nodes.
        OutboundFlowState state = state(stream);
        int bytesWritten = state.writeBytes(allowance);
        allowance -= bytesWritten;

        if (allowance <= 0 || stream.isLeaf()) {
            // Nothing left to do in this sub tree.
            return;
        }

        // Clip the remaining connection flow control window by the allowance.
        int remainingWindow = min(allowance, connectionWindow());

        // The total number of unallocated bytes from the children of this node.
        int unallocatedBytes = state.priorityBytes() - state.streamableBytes();

        // Optimization. If the window is big enough to fit all the data. Just write everything
        // and skip the priority algorithm.
        if (unallocatedBytes <= remainingWindow) {
            for (Http2Stream child : stream.children()) {
                writeAllowedBytes(child, state(child).unallocatedPriorityBytes());
            }
            return;
        }

        // Copy and sort the children of this node. They are sorted in ascending order the total
        // priority bytes for the subtree scaled by the weight of the node. The algorithm gives
        // preference to nodes that appear later in the list, since the weight of each node
        // increases in value as the list is iterated. This means that with this node ordering,
        // the most bytes will be written to those nodes with the largest aggregate number of
        // bytes and the highest priority.
        List<Http2Stream> states = new ArrayList<Http2Stream>(stream.children());
        Collections.sort(states, DATA_WEIGHT);

        // Iterate over the children and spread the remaining bytes across them as is appropriate
        // based on the weights. This algorithm loops over all of the children more than once,
        // although it should typically only take a few passes to complete. In each pass we
        // give a node its share of the current remaining bytes. The node's weight and bytes
        // allocated are then decremented from the totals, so that the subsequent
        // nodes split the difference. If after being processed, a node still has writable data,
        // it is added back to the queue for further processing in the next pass.
        int remainingWeight = stream.totalChildWeights();
        int nextTail = 0;
        int unallocatedBytesForNextPass = 0;
        int remainingWeightForNextPass = 0;
        for (int head = 0, tail = states.size();; ++head) {
            if (head >= tail) {
                // We've reached the end one pass of the nodes. Reset the totals based on
                // the nodes that were re-added to the deque since they still have data available.
                unallocatedBytes = unallocatedBytesForNextPass;
                remainingWeight = remainingWeightForNextPass;
                unallocatedBytesForNextPass = 0;
                remainingWeightForNextPass = 0;
                head = 0;
                tail = nextTail;
                nextTail = 0;
            }

            // Get the next state, or break if nothing to do.
            if (head >= tail) {
                break;
            }
            Http2Stream next = states.get(head);
            OutboundFlowState nextState = state(next);
            int weight = next.weight();

            // Determine the value (in bytes) of a single unit of weight.
            double dataToWeightRatio =
                    min(unallocatedBytes, remainingWindow) / (double) remainingWeight;
            unallocatedBytes -= nextState.unallocatedPriorityBytes();
            remainingWeight -= weight;

            if (dataToWeightRatio > 0.0 && nextState.unallocatedPriorityBytes() > 0) {

                // Determine the portion of the current writable data that is assigned to this
                // node.
                int writableChunk = (int) (weight * dataToWeightRatio);

                // Clip the chunk allocated by the total amount of unallocated data remaining in
                // the node.
                int allocatedChunk = min(writableChunk, nextState.unallocatedPriorityBytes());

                // Update the remaining connection window size.
                remainingWindow -= allocatedChunk;

                // Mark these bytes as allocated.
                nextState.allocatePriorityBytes(allocatedChunk);
                if (nextState.unallocatedPriorityBytes() > 0) {
                    // There is still data remaining for this stream. Add it back to the queue
                    // for the next pass.
                    unallocatedBytesForNextPass += nextState.unallocatedPriorityBytes();
                    remainingWeightForNextPass += weight;
                    states.set(nextTail++, next);
                    continue;
                }
            }

            if (nextState.allocatedPriorityBytes() > 0) {
                // Write the allocated data for this stream.
                writeAllowedBytes(next, nextState.allocatedPriorityBytes());

                // We're done with this node. Remark all bytes as unallocated for future
                // invocations.
                nextState.allocatePriorityBytes(0);
            }
        }
    }

    /**
     * The outbound flow control state for a single stream.
     */
    private final class OutboundFlowState implements FlowState {
        private final Queue<Frame> pendingWriteQueue;
        private final Http2Stream stream;
        private int window = initialWindowSize;
        private int pendingBytes;
        private int priorityBytes;
        private int allocatedPriorityBytes;

        OutboundFlowState(Http2Stream stream) {
            this.stream = stream;
            pendingWriteQueue = new ArrayDeque<Frame>(2);
        }

        @Override
        public int window() {
            return window;
        }

        /**
         * Increments the flow control window for this stream by the given delta and returns the new
         * value.
         */
        int incrementStreamWindow(int delta) throws Http2Exception {
            if (delta > 0 && Integer.MAX_VALUE - delta < window) {
                throw new Http2StreamException(stream.id(), FLOW_CONTROL_ERROR,
                        "Window size overflow for stream: " + stream.id());
            }
            int previouslyStreamable = streamableBytes();
            window += delta;

            // Update this branch of the priority tree if the streamable bytes have changed for this
            // node.
            incrementPriorityBytes(streamableBytes() - previouslyStreamable);
            return window;
        }

        /**
         * Returns the maximum writable window (minimum of the stream and connection windows).
         */
        int writableWindow() {
            return min(window, connectionWindow());
        }

        /**
         * Returns the number of pending bytes for this node that will fit within the
         * {@link #window}. This is used for the priority algorithm to determine the aggregate total
         * for {@link #priorityBytes} at each node. Each node only takes into account it's stream
         * window so that when a change occurs to the connection window, these values need not
         * change (i.e. no tree traversal is required).
         */
        int streamableBytes() {
            return max(0, min(pendingBytes, window));
        }

        /**
         * The aggregate total of all {@link #streamableBytes()} for subtree rooted at this node.
         */
        int priorityBytes() {
            return priorityBytes;
        }

        /**
         * Used by the priority algorithm to allocate bytes to this stream.
         */
        void allocatePriorityBytes(int bytes) {
            allocatedPriorityBytes += bytes;
        }

        /**
         * Used by the priority algorithm to get the intermediate allocation of bytes to this
         * stream.
         */
        int allocatedPriorityBytes() {
            return allocatedPriorityBytes;
        }

        /**
         * Used by the priority algorithm to determine the number of writable bytes that have not
         * yet been allocated.
         */
        int unallocatedPriorityBytes() {
            return priorityBytes - allocatedPriorityBytes;
        }

        /**
         * Creates a new frame with the given values but does not add it to the pending queue.
         */
        Frame newFrame(ByteBuf data, int padding, boolean endStream, FrameWriter writer) {
            return new Frame(data, padding, endStream, writer);
        }

        /**
         * Indicates whether or not there are frames in the pending queue.
         */
        boolean hasFrame() {
            return !pendingWriteQueue.isEmpty();
        }

        /**
         * Returns the the head of the pending queue, or {@code null} if empty or the current window
         * size is zero.
         */
        Frame peek() {
            if (window > 0) {
                return pendingWriteQueue.peek();
            }
            return null;
        }

        /**
         * Clears the pending queue and writes errors for each remaining frame.
         */
        void clear() {
            for (;;) {
                Frame frame = pendingWriteQueue.poll();
                if (frame == null) {
                    break;
                }
                frame.writeError(format(STREAM_CLOSED,
                        "Stream closed before write could take place"));
            }
        }

        /**
         * Writes up to the number of bytes from the pending queue. May write less if limited by the
         * writable window, by the number of pending writes available, or because a frame does not
         * support splitting on arbitrary boundaries.
         */
        int writeBytes(int bytes) throws Http2Exception {
            int bytesWritten = 0;
            if (!stream.localSideOpen()) {
                return bytesWritten;
            }

            int maxBytes = min(bytes, writableWindow());
            while (bytesWritten < maxBytes && hasFrame()) {
                Frame pendingWrite = peek();
                if (maxBytes >= pendingWrite.size()) {
                    // Window size is large enough to send entire data frame
                    bytesWritten += pendingWrite.size();
                    pendingWrite.write();
                } else {
                    // We can send a partial frame
                    Frame partialFrame = pendingWrite.split(maxBytes);
                    bytesWritten += partialFrame.size();
                    partialFrame.write();
                }

                // Update the threshold.
                maxBytes = min(bytes - bytesWritten, writableWindow());
            }
            return bytesWritten;
        }

        /**
         * Recursively increments the priority bytes for this branch in the priority tree starting
         * at the current node.
         */
        private void incrementPriorityBytes(int numBytes) {
            if (numBytes != 0) {
                priorityBytes += numBytes;
                if (!stream.isRoot()) {
                    state(stream.parent()).incrementPriorityBytes(numBytes);
                }
            }
        }

        /**
         * A wrapper class around the content of a data frame.
         */
        private final class Frame {
            private final ByteBuf data;
            private final int padding;
            private final boolean endStream;
            private final FrameWriter writer;
            private boolean enqueued;

            Frame(ByteBuf data, int padding, boolean endStream, FrameWriter writer) {
                this.data = data;
                this.padding = padding;
                this.endStream = endStream;
                this.writer = writer;
            }

            int size() {
                return data.readableBytes();
            }

            void enqueue() {
                if (!enqueued) {
                    enqueued = true;
                    pendingWriteQueue.offer(this);

                    // Increment the number of pending bytes for this stream.
                    incrementPendingBytes(data.readableBytes());
                }
            }

            /**
             * Increments the number of pending bytes for this node. If there was any change to the
             * number of bytes that fit into the stream window, then {@link #incrementPriorityBytes} to
             * recursively update this branch of the priority tree.
             */
            private void incrementPendingBytes(int numBytes) {
                int previouslyStreamable = streamableBytes();
                pendingBytes += numBytes;

                int delta = streamableBytes() - previouslyStreamable;
                incrementPriorityBytes(delta);
            }

            /**
             * Writes the frame and decrements the stream and connection window sizes. If the frame
             * is in the pending queue, the written bytes are removed from this branch of the
             * priority tree.
             */
            void write() throws Http2Exception {
                // Using a do/while loop because if the buffer is empty we still need to call
                // the writer once to send the empty frame.
                do {
                    int bytesToWrite = data.readableBytes();
                    int frameBytes = Math.min(bytesToWrite, writer.maxFrameSize());
                    if (frameBytes == bytesToWrite) {
                        // All the bytes fit into a single HTTP/2 frame, just send it all.
                        connectionState().incrementStreamWindow(-bytesToWrite);
                        incrementStreamWindow(-bytesToWrite);
                        ByteBuf slice = data.readSlice(bytesToWrite);
                        writer.writeFrame(stream.id(), slice, padding, endStream);
                        decrementPendingBytes(bytesToWrite);
                        return;
                    }

                    // Split a chunk that will fit into a single HTTP/2 frame and write it.
                    Frame frame = split(frameBytes);
                    frame.write();
                } while (data.isReadable());
            }

            /**
             * Discards this frame, writing an error. If this frame is in the pending queue, the
             * unwritten bytes are removed from this branch of the priority tree.
             */
            void writeError(Http2Exception cause) {
                decrementPendingBytes(data.readableBytes());
                data.release();
                writer.setFailure(cause);
            }

            /**
             * Creates a new frame that is a view of this frame's data buffer starting at the
             * current read index with the given number of bytes. The reader index on the input
             * frame is then advanced by the number of bytes. The returned frame will not have
             * end-of-stream set and it will not be automatically placed in the pending queue.
             *
             * @param maxBytes the maximum number of bytes that is allowed in the created frame.
             * @return the partial frame.
             */
            Frame split(int maxBytes) {
                // TODO: Should padding be included in the chunks or only the last frame?
                maxBytes = min(maxBytes, data.readableBytes());
                Frame frame = new Frame(data.readSlice(maxBytes).retain(), 0, false, writer);
                decrementPendingBytes(maxBytes);
                return frame;
            }

            /**
             * If this frame is in the pending queue, decrements the number of pending bytes for the
             * stream.
             */
            void decrementPendingBytes(int bytes) {
                if (enqueued) {
                    incrementPendingBytes(-bytes);
                }
            }
        }
    }
}
