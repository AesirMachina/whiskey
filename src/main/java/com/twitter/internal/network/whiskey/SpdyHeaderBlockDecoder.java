/*
 * Copyright 2014 The Netty Project
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

package com.twitter.internal.network.whiskey;

import java.nio.ByteBuffer;

abstract class SpdyHeaderBlockDecoder {

    static SpdyHeaderBlockDecoder newInstance(SpdyVersion spdyVersion, SpdyFrameDecoderDelegate delegate, int maxHeaderSize) {
        return new SpdyHeaderBlockZlibDecoder(spdyVersion, delegate, maxHeaderSize);
    }

    /**
     * Decodes a SPDY Header Block, adding the Name/Value pairs to the given Headers frame.
     * If the header block is malformed, the Headers frame will be marked as invalid.
     * A stream error with status code PROTOCOL_ERROR must be issued in response to an invalid frame.
     *
     * @param headerBlock the header block to decode
     * @param streamId the id of the stream associated with this header block
     * @throws Exception If the header block is malformed in a way that prevents any future
     *                   decoding of any other header blocks, an exception will be thrown.
     *                   A session error with status code PROTOCOL_ERROR must be issued.
     */
    abstract void decode(ByteBuffer headerBlock, int streamId) throws Exception;

    abstract void endHeaderBlock();

    abstract void end();
}
