package com.twitter.internal.network.whiskey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;

import static com.twitter.internal.network.whiskey.SpdyConstants.*;

class SpdySession implements Session, SpdyFrameDecoderDelegate {

    private static final Map<Origin, SpdySettings> storedSettings = new HashMap<>();
    private static final int DEFAULT_BUFFER_SIZE = 65536;

    private final Origin origin;
    private final ClientConfiguration configuration;
    private final SessionCloseFuture sessionCloseFuture;
    private final SessionManager manager;
    private final SpdyFrameDecoder frameDecoder;
    private final SpdyFrameEncoder frameEncoder;
    private final SpdyStreamManager activeStreams = new SpdyStreamManager();
    private final Socket socket;

    private ByteBuffer inputBuffer;
    private Map<Integer, Long> sentPingMap = new TreeMap<>();
    private int lastGoodStreamId = 0;
    private int nextStreamId = 1;
    private int nextPingId = 1;
    private int initialSendWindow = DEFAULT_INITIAL_WINDOW_SIZE;
    private int initialReceiveWindow;
    private int sessionSendWindow = DEFAULT_INITIAL_WINDOW_SIZE;
    private int sessionReceiveWindow;
    private int localMaxConcurrentStreams = 0;
    private int remoteMaxConcurrentStreams = 100;
    private long latency = -1;
    private boolean receivedGoAwayFrame = false;
    private boolean sentGoAwayFrame = false;
    private boolean active = false;

    SpdySession(SessionManager manager, ClientConfiguration configuration, Socket socket) {

        this.configuration = configuration;
        this.manager = manager;
        this.origin = manager.getOrigin();
        this.socket = socket;

        frameDecoder = new SpdyFrameDecoder(SpdyVersion.SPDY_3_1, this);
        frameEncoder = new SpdyFrameEncoder(SpdyVersion.SPDY_3_1);

        initialReceiveWindow = configuration.getStreamReceiveWindow();
        sessionReceiveWindow = configuration.getSessionReceiveWindow();
        localMaxConcurrentStreams = configuration.getMaxPushStreams();

        sessionCloseFuture = new SessionCloseFuture();
        socket.getCloseFuture().addListener(new SocketCloseListener());
        sendClientSettings();
        sendPing();

        int windowDelta = sessionReceiveWindow - DEFAULT_INITIAL_WINDOW_SIZE;
        sendWindowUpdate(SPDY_SESSION_STREAM_ID, windowDelta);
        manager.poll(this, getCapacity());

        inputBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        socket.read(inputBuffer).addListener(new SocketReadListener());
    }

    @Override
    public boolean isOpen() {
        return !receivedGoAwayFrame && socket.isConnected();
    }

    @Override
    public boolean isConnected() {
        return socket.isConnected();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public boolean isClosed() {
        return receivedGoAwayFrame || !socket.isConnected();
    }

    @Override
    public boolean isDraining() {
        return receivedGoAwayFrame && socket.isConnected();
    }

    @Override
    public boolean isDisconnected() {
        return !socket.isConnected();
    }

    @Override
    public int getCapacity() {
        return remoteMaxConcurrentStreams - activeStreams.getLocalSize();
    }

    @Override
    public boolean wasActive() {
        return false;
    }

    @Override
    public void queue(RequestOperation operation) {

        SpdyStream stream = new SpdyStream(operation);
        stream.open(nextStreamId, initialSendWindow, configuration.getStreamReceiveWindow());
    }

    @Override
    public CloseFuture getCloseFuture() {
        return sessionCloseFuture;
    }

    /* SpdyFrameDecoderDelegate */
    @Override
    public void readDataFrame(int streamId, boolean last, ByteBuffer data) {
    /*
     * SPDY Data frame processing requirements:
     *
     * If an endpoint receives a data frame for a Stream-ID which is not open
     * and the endpoint has not sent a GOAWAY frame, it must issue a stream error
     * with the error code INVALID_STREAM for the Stream-ID.
     *
     * If an endpoint which created the stream receives a data frame before receiving
     * a SYN_REPLY on that stream, it is a protocol error, and the recipient must
     * issue a stream error with the status code PROTOCOL_ERROR for the Stream-ID.
     *
     * If an endpoint receives multiple data frames for invalid Stream-IDs,
     * it may close the session.
     *
     * If an endpoint refuses a stream it must ignore any data frames for that stream.
     *
     * If an endpoint receives a data frame after the stream is half-closed from the
     * sender, it must send a RST_STREAM frame with the status STREAM_ALREADY_CLOSED.
     *
     * If an endpoint receives a data frame after the stream is closed, it must send
     * a RST_STREAM frame with the status PROTOCOL_ERROR.
     */

        SpdyStream stream = activeStreams.get(streamId);

        // Check if session flow control is violated
        if (sessionReceiveWindow < data.remaining()) {
            closeWithStatus(SPDY_SESSION_PROTOCOL_ERROR);
            return;
        }

        // Check if we received a data frame for a valid Stream-ID
        if (stream == null) {
            if (streamId < lastGoodStreamId) {
                sendRstStream(streamId, SPDY_STREAM_PROTOCOL_ERROR);
            } else if (!sentGoAwayFrame) {
                sendRstStream(streamId, SPDY_STREAM_INVALID_STREAM);
            }
            return;
        }

        // Check if we received a data frame for a stream which is half-closed
        if (stream.isClosedRemotely()) {
            sendRstStream(streamId, SPDY_STREAM_STREAM_ALREADY_CLOSED);
            return;
        }

        // Check if we received a data frame before receiving a SYN_REPLY
        if (stream.isLocal() && !stream.hasRecievedReply()) {
            sendRstStream(streamId, SPDY_STREAM_PROTOCOL_ERROR);
            return;
        }

    /*
     * SPDY Data frame flow control processing requirements:
     *
     * Recipient should not send a WINDOW_UPDATE frame as it consumes the last data frame.
     */

        // Check if stream flow control is violated
        if (stream.getReceiveWindow() < data.remaining()) {
            sendRstStream(streamId, SPDY_STREAM_FLOW_CONTROL_ERROR);
            return;
        }

        // Update session receive window size
        sessionReceiveWindow -= data.remaining();

        // Send a WINDOW_UPDATE frame if less than half the sesion window size remains
        if (sessionReceiveWindow <= initialReceiveWindow / 2) {
            int deltaWindowSize = initialReceiveWindow - sessionReceiveWindow;
            sendWindowUpdate(SPDY_SESSION_STREAM_ID, deltaWindowSize);
        }

        // Update stream receive window size
        stream.reduceReceiveWindow(data.remaining());

        if (stream.getReceiveWindow() <= initialReceiveWindow / 2) {
            int deltaWindowSize = initialReceiveWindow - stream.getReceiveWindow();
            stream.increaseReceiveWindow(deltaWindowSize);
            sendWindowUpdate(streamId, deltaWindowSize);
        }

        stream.onData(data, last);

        if (last) {
            stream.closeRemotely();
            if (stream.isClosed()) {
                activeStreams.remove(stream);
            }
        }
    }

    @Override
    public void readSynStreamFrame(int streamId, int associatedToStreamId, byte priority, boolean last, boolean unidirectional) {
    /*
     * SPDY SYN_STREAM frame processing requirements:
     *
     * If an endpoint receives a SYN_STREAM with a Stream-ID that is less than
     * any previously received SYN_STREAM, it must issue a session error with
     * the status PROTOCOL_ERROR.
     *
     * If an endpoint receives multiple SYN_STREAM frames with the same active
     * Stream-ID, it must issue a stream error with the status code PROTOCOL_ERROR.
     *
     * The recipient can reject a stream by sending a stream error with the
     * status code REFUSED_STREAM.
     */

        if (streamId <= lastGoodStreamId) {
            closeWithStatus(SPDY_SESSION_PROTOCOL_ERROR);
            return;
        }

        if (receivedGoAwayFrame || activeStreams.getRemoteSize() >= localMaxConcurrentStreams) {
            sendRstStream(streamId, SPDY_STREAM_REFUSED_STREAM);
            return;
        }

        SpdyStream stream = new SpdyStream(false, priority);
        stream.open(streamId, initialSendWindow, initialReceiveWindow);

        lastGoodStreamId = streamId;
        activeStreams.add(stream);
    }

    @Override
    public void readSynReplyFrame(int streamId, boolean last) {
    /*
     * SPDY SYN_REPLY frame processing requirements:
     *
     * If an endpoint receives multiple SYN_REPLY frames for the same active Stream-ID
     * it must issue a stream error with the status code STREAM_IN_USE.
     */

        SpdyStream stream = activeStreams.get(streamId);

        // Check if this is a reply for an active stream
        if (stream == null) {
            sendRstStream(streamId, SPDY_STREAM_INVALID_STREAM);
            return;
        }

        // Check if we have received multiple frames for the same Stream-ID
        if (stream.hasRecievedReply()) {
            sendRstStream(streamId, SPDY_STREAM_STREAM_IN_USE);
            return;
        }

        active = true;
        stream.onReply();

        if (last) {
            stream.closeRemotely();
            // Defer removing stream from activeStreams until we receive headersEnd
        }
    }

    @Override
    public void readRstStreamFrame(int streamId, int statusCode) {
    /*
    * SPDY RST_STREAM frame processing requirements:
    *
    * After receiving a RST_STREAM on a stream, the receiver must not send
    * additional frames on that stream.
    *
    * An endpoint must not send a RST_STREAM in response to a RST_STREAM.
    */

        SpdyStream stream = activeStreams.get(streamId);

        if (stream != null) {
            activeStreams.remove(stream);
            stream.close(new SpdyStreamException(statusCode));
        }
    }

    @Override
    public void readSettingsFrame(boolean clearPersisted) {
    /*
     * SPDY SETTINGS frame processing requirements:
     *
     * When a client connects to a server, and the server persists settings
     * within the client, the client should return the persisted settings on
     * future connections to the same origin and IP address and TCP port (the
     * "origin" is the set of scheme, host, and port from the URI).
     */

        if (clearPersisted) {
            storedSettings.remove(origin);
        }
    }

    @Override
    public void readSetting(int id, int value, boolean persistValue, boolean persisted) {

        if (persisted) {
            closeWithStatus(SPDY_SESSION_PROTOCOL_ERROR);
            return;
        }

        int delta;
        switch(id) {

            case SpdySettings.MAX_CONCURRENT_STREAMS:
                delta = value - remoteMaxConcurrentStreams;
                remoteMaxConcurrentStreams = value;
                if (delta > 0) {
                    manager.poll(this, delta);
                }
                break;

            case SpdySettings.INITIAL_WINDOW_SIZE:
                delta = value - initialSendWindow;
                for (SpdyStream stream : activeStreams) {
                    if (!stream.isClosedLocally()) {
                        stream.increaseSendWindow(delta);
                        if (delta > 0) {
                            sendData(stream);
                        }
                    }
                }
                break;

            default:
        }

        if (persistValue) {
            SpdySettings settings = storedSettings.get(origin);
            if (settings == null) {
                settings = new SpdySettings();
                storedSettings.put(origin, settings);
            }
            settings.setValue(id, value);
        }
    }

    @Override
    public void readSettingsEnd() {
    }

    @Override
    public void readPingFrame(int id) {
    /*
     * SPDY PING frame processing requirements:
     *
     * Receivers of a PING frame should send an identical frame to the sender
     * as soon as possible.
     *
     * Receivers of a PING frame must ignore frames that it did not initiate
     */

        if (id % 2 == 0) {
            sendPingResponse(id);
        } else {
            Long sentTime = sentPingMap.get(id);
            if (sentTime == null) {
                return;
            }

            sentPingMap.remove(id);
            latency = sentTime - System.currentTimeMillis();
        }
    }

    @Override
    public void readGoAwayFrame(int lastGoodStreamId, int statusCode) {

        receivedGoAwayFrame = true;

        for (SpdyStream stream : activeStreams) {
            if (stream.isLocal() && stream.getStreamId() > lastGoodStreamId) {
                stream.close(new SpdySessionException(statusCode));
                activeStreams.remove(stream);
            }
        }
    }

    @Override
    public void readHeadersFrame(int streamId, boolean last) {

        SpdyStream stream = activeStreams.get(streamId);

        if (stream == null || stream.isClosedRemotely()) {
            sendRstStream(streamId, SPDY_STREAM_INVALID_STREAM);
        }
    }

    @Override
    public void readWindowUpdateFrame(int streamId, int deltaWindowSize) {
    /*
     * SPDY WINDOW_UPDATE frame processing requirements:
     *
     * Receivers of a WINDOW_UPDATE that cause the window size to exceed 2^31
     * must send a RST_STREAM with the status code FLOW_CONTROL_ERROR.
     *
     * Sender should ignore all WINDOW_UPDATE frames associated with a stream
     * after sending the last frame for the stream.
     */

        if (streamId == SPDY_SESSION_STREAM_ID) {
            // Check for numerical overflow
            if (sessionSendWindow > Integer.MAX_VALUE - deltaWindowSize) {
                closeWithStatus(SPDY_SESSION_PROTOCOL_ERROR);
                return;
            }

            sessionSendWindow += deltaWindowSize;
            for (SpdyStream stream : activeStreams) {
                sendData(stream);
                if (sessionSendWindow == 0) break;
            }

            return;
        }

        SpdyStream stream = activeStreams.get(streamId);

        // Ignore frames for non-existent or half-closed streams
        if (stream == null || stream.isClosedLocally()) {
            return;
        }

        // Check for numerical overflow
        if (stream.getSendWindow() > Integer.MAX_VALUE - deltaWindowSize) {
            sendRstStream(streamId, SPDY_STREAM_FLOW_CONTROL_ERROR);
            activeStreams.remove(stream);
            stream.close(new SpdyStreamException("flow control error"));
        }

        stream.increaseSendWindow(deltaWindowSize);
        sendData(stream);
    }

    @Override
    public void readHeader(int streamId, Header header) {

        SpdyStream stream = activeStreams.get(streamId);
        assert(stream != null); // Should have been caught when frame was decoded

        try {
            stream.onHeader(header);
        } catch (IOException e) {
            activeStreams.remove(stream);
            stream.close(e);
        }
    }

    @Override
    public void readHeadersEnd(int streamId) {

        SpdyStream stream = activeStreams.get(streamId);
        assert(stream != null); // Should have been caught when frame was decoded

        if (stream.isClosed()) {
            activeStreams.remove(stream);
        }
    }

    @Override
    public void readFrameSkipped(int streamId, String message) {

    }

    @Override
    public void readFrameError(String message) {

    }

    public void sendRstStream(int streamId, int streamStatus) {
        socket.write(frameEncoder.encodeRstStreamFrame(streamId, streamStatus));
    }

    public void sendWindowUpdate(int streamId, int delta) {
        socket.write(frameEncoder.encodeWindowUpdateFrame(streamId, delta));
    }

    private void sendData(SpdyStream stream) {

    }

    private void sendClientSettings() {
        SpdySettings settings = new SpdySettings();
        settings.setValue(SpdySettings.INITIAL_WINDOW_SIZE, initialReceiveWindow);
        socket.write(frameEncoder.encodeSettingsFrame(settings));
    }

    private void sendPing() {

        final int pingId = nextPingId;
        nextPingId += 2;

        Socket.WriteFuture pingFuture = socket.write(frameEncoder.encodePingFrame(pingId));

        pingFuture.addListener(new Listener<Long>() {
            @Override
            public void onComplete(Long result) {
                sentPingMap.put(pingId, PlatformAdapter.instance().timestamp());
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public Executor getExecutor() {
                return Inline.INSTANCE;
            }
        });
    }

    private void sendPingResponse(int pingId) {
        socket.write(frameEncoder.encodePingFrame(pingId));
    }

    public void closeWithStatus(int sessionStatus) {

    }

    private class SocketReadListener implements Listener<ByteBuffer> {

        @Override
        public void onComplete(ByteBuffer result) {
            frameDecoder.decode(result);
            result.compact();
            socket.read(result).addListener(new SocketReadListener());
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public Executor getExecutor() {
            return Inline.INSTANCE;
        }
    }

    private class SocketWriteLogger implements Listener<Long> {
        String message;

        SocketWriteLogger(final String message) {
            this.message = message;
        }

        @Override
        public void onComplete(Long result) {
            // TODO: simple platform-specific logging
//            WhiskeyLog.d(message);
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public Executor getExecutor() {
            return Inline.INSTANCE;
        }
    }

    private class SocketCloseListener implements Listener<Origin> {

        /**
         * Occurs when the client has initiated the connection closure.
         */
        @Override
        public void onComplete(Origin result) {
            // We should never attempt to close the socket if there are active streams.
            assert activeStreams.size() == 0;
            sessionCloseFuture.set(SpdySession.this);
        }

        /**
         * Occurs when the connection closes unexpectedly.
         * @param throwable the cause of the connection closure
         */
        @Override
        public void onError(Throwable throwable) {

            Iterator<SpdyStream> i = activeStreams.iterator();
            while (i.hasNext()) {
                SpdyStream stream = i.next();
                stream.close(throwable);
                i.remove();
            }
            sessionCloseFuture.fail(throwable);
        }

        @Override
        public Executor getExecutor() {
            return Inline.INSTANCE;
        }
    }

    class SessionCloseFuture extends CompletableFuture<Session> implements Session.CloseFuture {
    }
}
