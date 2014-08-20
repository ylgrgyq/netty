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

/**
 * Controls the inbound flow of data frames from the remote endpoint.
 */
public interface Http2InboundFlowController {

    /**
     * A writer of window update frames.
     */
    interface FrameWriter {

        /**
         * Writes a window update frame to the remote endpoint.
         */
        void writeFrame(int streamId, int windowSizeIncrement) throws Http2Exception;
    }

    /**
     * Sets the initial inbound flow control window size and updates all stream window sizes by the
     * delta. This is called as part of the processing for an outbound SETTINGS frame.
     *
     * @param newWindowSize the new initial window size.
     * @throws Http2Exception thrown if any protocol-related error occurred.
     */
    void initialInboundWindowSize(int newWindowSize) throws Http2Exception;

    /**
     * Gets the initial inbound flow control window size.
     */
    int initialInboundWindowSize();

    /**
     * Applies flow control for the received data frame.
     *
     * @param streamId the ID of the stream receiving the data
     * @param data the data portion of the data frame. Does not contain padding.
     * @param padding the amount of padding received in the original frame.
     * @param endOfStream indicates whether this is the last frame for the stream.
     * @param frameWriter allows this flow controller to send window updates to the remote endpoint.
     * @throws Http2Exception thrown if any protocol-related error occurred.
     */
    void applyInboundFlowControl(int streamId, ByteBuf data, int padding, boolean endOfStream,
            FrameWriter frameWriter) throws Http2Exception;
}
