package com.lx.learngitprojectdemo;

/*
 * #%L
 * NanoHttpd-Websocket
 * %%
 * Copyright (C) 2012 - 2015 nanohttpd
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the nanohttpd nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

import com.lx.learngitprojectdemo.NanoWSD.WebSocketFrame.CloseCode;
import com.lx.learngitprojectdemo.NanoWSD.WebSocketFrame.OpCode;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
public abstract class NanoWSD extends NanoHTTPD {

    public static enum State {
        UNCONNECTED,
        CONNECTING,
        OPEN,
        CLOSING,
        CLOSED
    }

    public static abstract class WebSocket {

        private final InputStream in;

        private OutputStream out;

        private NanoWSD.WebSocketFrame.OpCode continuousOpCode = null;

        private final List<NanoWSD.WebSocketFrame> continuousFrames = new LinkedList<NanoWSD.WebSocketFrame>();

        private NanoWSD.State state = NanoWSD.State.UNCONNECTED;

        private final NanoHTTPD.IHTTPSession handshakeRequest;

        private final NanoHTTPD.Response handshakeResponse = new NanoHTTPD.Response(NanoHTTPD.Response.Status.SWITCH_PROTOCOL, null, (InputStream) null, 0) {

            @Override
            protected void send(OutputStream out) {
                NanoWSD.WebSocket.this.out = out;
                NanoWSD.WebSocket.this.state = NanoWSD.State.CONNECTING;
                super.send(out);
                NanoWSD.WebSocket.this.state = NanoWSD.State.OPEN;
                NanoWSD.WebSocket.this.onOpen();
                readWebsocket();
            }
        };

        public WebSocket(NanoHTTPD.IHTTPSession handshakeRequest) {
            this.handshakeRequest = handshakeRequest;
            this.in = handshakeRequest.getInputStream();

            this.handshakeResponse.addHeader(NanoWSD.HEADER_UPGRADE, NanoWSD.HEADER_UPGRADE_VALUE);
            this.handshakeResponse.addHeader(NanoWSD.HEADER_CONNECTION, NanoWSD.HEADER_CONNECTION_VALUE);
        }

        public boolean isOpen() {
            return state == NanoWSD.State.OPEN;
        }

        protected abstract void onOpen();

        protected abstract void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote);

        protected abstract void onMessage(NanoWSD.WebSocketFrame message);

        protected abstract void onPong(NanoWSD.WebSocketFrame pong);

        protected abstract void onException(IOException exception);

        /**
         * Debug method. <b>Do not Override unless for debug purposes!</b>
         *
         * @param frame
         *            The received WebSocket Frame.
         */
        protected void debugFrameReceived(NanoWSD.WebSocketFrame frame) {
        }

        /**
         * Debug method. <b>Do not Override unless for debug purposes!</b><br>
         * This method is called before actually sending the frame.
         *
         * @param frame
         *            The sent WebSocket Frame.
         */
        protected void debugFrameSent(NanoWSD.WebSocketFrame frame) {
        }

        public void close(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) throws IOException {
            NanoWSD.State oldState = this.state;
            this.state = NanoWSD.State.CLOSING;
            if (oldState == NanoWSD.State.OPEN) {
                sendFrame(new WebSocketFrame.CloseFrame(code, reason));
            } else {
                doClose(code, reason, initiatedByRemote);
            }
        }

        private void doClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            if (this.state == NanoWSD.State.CLOSED) {
                return;
            }
            if (this.in != null) {
                try {
                    this.in.close();
                } catch (IOException e) {
                    NanoWSD.LOG.log(Level.FINE, "close failed", e);
                }
            }
            if (this.out != null) {
                try {
                    this.out.close();
                } catch (IOException e) {
                    NanoWSD.LOG.log(Level.FINE, "close failed", e);
                }
            }
            this.state = NanoWSD.State.CLOSED;
            onClose(code, reason, initiatedByRemote);
        }

        // --------------------------------IO--------------------------------------

        public NanoHTTPD.IHTTPSession getHandshakeRequest() {
            return this.handshakeRequest;
        }

        public NanoHTTPD.Response getHandshakeResponse() {
            return this.handshakeResponse;
        }

        private void handleCloseFrame(NanoWSD.WebSocketFrame frame) throws IOException {
            WebSocketFrame.CloseCode code = WebSocketFrame.CloseCode.NormalClosure;
            String reason = "";
            if (frame instanceof WebSocketFrame.CloseFrame) {
                code = ((WebSocketFrame.CloseFrame) frame).getCloseCode();
                reason = ((WebSocketFrame.CloseFrame) frame).getCloseReason();
            }
            if (this.state == NanoWSD.State.CLOSING) {
                // Answer for my requested close
                doClose(code, reason, false);
            } else {
                close(code, reason, true);
            }
        }

        private void handleFrameFragment(NanoWSD.WebSocketFrame frame) throws IOException {
            if (frame.getOpCode() != WebSocketFrame.OpCode.Continuation) {
                // First
                if (this.continuousOpCode != null) {
                    throw new NanoWSD.WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Previous continuous frame sequence not completed.");
                }
                this.continuousOpCode = frame.getOpCode();
                this.continuousFrames.clear();
                this.continuousFrames.add(frame);
            } else if (frame.isFin()) {
                // Last
                if (this.continuousOpCode == null) {
                    throw new NanoWSD.WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Continuous frame sequence was not started.");
                }
                this.continuousFrames.add(frame);
                onMessage(new NanoWSD.WebSocketFrame(this.continuousOpCode, this.continuousFrames));
                this.continuousOpCode = null;
                this.continuousFrames.clear();
            } else if (this.continuousOpCode == null) {
                // Unexpected
                throw new NanoWSD.WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Continuous frame sequence was not started.");
            } else {
                // Intermediate
                this.continuousFrames.add(frame);
            }
        }

        private void handleWebsocketFrame(NanoWSD.WebSocketFrame frame) throws IOException {
            debugFrameReceived(frame);
            if (frame.getOpCode() == WebSocketFrame.OpCode.Close) {
                handleCloseFrame(frame);
            } else if (frame.getOpCode() == WebSocketFrame.OpCode.Ping) {
                sendFrame(new NanoWSD.WebSocketFrame(WebSocketFrame.OpCode.Pong, true, frame.getBinaryPayload()));
            } else if (frame.getOpCode() == WebSocketFrame.OpCode.Pong) {
                onPong(frame);
            } else if (!frame.isFin() || frame.getOpCode() == WebSocketFrame.OpCode.Continuation) {
                handleFrameFragment(frame);
            } else if (this.continuousOpCode != null) {
                throw new NanoWSD.WebSocketException(WebSocketFrame.CloseCode.ProtocolError, "Continuous frame sequence not completed.");
            } else if (frame.getOpCode() == OpCode.Text || frame.getOpCode() == OpCode.Binary) {
                onMessage(frame);
            } else {
                throw new NanoWSD.WebSocketException(CloseCode.ProtocolError, "Non control or continuous frame expected.");
            }
        }

        // --------------------------------Close-----------------------------------

        public void ping(byte[] payload) throws IOException {
            sendFrame(new NanoWSD.WebSocketFrame(OpCode.Ping, true, payload));
        }

        // --------------------------------Public
        // Facade---------------------------

        private void readWebsocket() {
            try {
                while (this.state == NanoWSD.State.OPEN) {
                    handleWebsocketFrame(NanoWSD.WebSocketFrame.read(this.in));
                }
            } catch (CharacterCodingException e) {
                onException(e);
                doClose(CloseCode.InvalidFramePayloadData, e.toString(), false);
            } catch (IOException e) {
                onException(e);
                if (e instanceof NanoWSD.WebSocketException) {
                    doClose(((NanoWSD.WebSocketException) e).getCode(), ((NanoWSD.WebSocketException) e).getReason(), false);
                }
            } finally {
                doClose(CloseCode.InternalServerError, "Handler terminated without closing the connection.", false);
            }
        }

        public void send(byte[] payload) throws IOException {
            sendFrame(new NanoWSD.WebSocketFrame(OpCode.Binary, true, payload));
        }

        public void send(String payload) throws IOException {
            sendFrame(new NanoWSD.WebSocketFrame(OpCode.Text, true, payload));
        }

        public synchronized void sendFrame(NanoWSD.WebSocketFrame frame) throws IOException {
            debugFrameSent(frame);
            frame.write(this.out);
        }
    }

    public static class WebSocketException extends IOException {

        private static final long serialVersionUID = 1L;

        private final CloseCode code;

        private final String reason;

        public WebSocketException(CloseCode code, String reason) {
            this(code, reason, null);
        }

        public WebSocketException(CloseCode code, String reason, Exception cause) {
            super(code + ": " + reason, cause);
            this.code = code;
            this.reason = reason;
        }

        public WebSocketException(Exception cause) {
            this(CloseCode.InternalServerError, cause.toString(), cause);
        }

        public CloseCode getCode() {
            return this.code;
        }

        public String getReason() {
            return this.reason;
        }
    }

    public static class WebSocketFrame {

        public static enum CloseCode {
            NormalClosure(1000),
            GoingAway(1001),
            ProtocolError(1002),
            UnsupportedData(1003),
            NoStatusRcvd(1005),
            AbnormalClosure(1006),
            InvalidFramePayloadData(1007),
            PolicyViolation(1008),
            MessageTooBig(1009),
            MandatoryExt(1010),
            InternalServerError(1011),
            TLSHandshake(1015);

            public static NanoWSD.WebSocketFrame.CloseCode find(int value) {
                for (NanoWSD.WebSocketFrame.CloseCode code : values()) {
                    if (code.getValue() == value) {
                        return code;
                    }
                }
                return null;
            }

            private final int code;

            private CloseCode(int code) {
                this.code = code;
            }

            public int getValue() {
                return this.code;
            }
        }

        public static class CloseFrame extends NanoWSD.WebSocketFrame {

            private static byte[] generatePayload(CloseCode code, String closeReason) throws CharacterCodingException {
                if (code != null) {
                    byte[] reasonBytes = text2Binary(closeReason);
                    byte[] payload = new byte[reasonBytes.length + 2];
                    payload[0] = (byte) (code.getValue() >> 8 & 0xFF);
                    payload[1] = (byte) (code.getValue() & 0xFF);
                    System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
                    return payload;
                } else {
                    return new byte[0];
                }
            }

            private CloseCode _closeCode;

            private String _closeReason;

            public CloseFrame(CloseCode code, String closeReason) throws CharacterCodingException {
                super(OpCode.Close, true, generatePayload(code, closeReason));
            }

            private CloseFrame(NanoWSD.WebSocketFrame wrap) throws CharacterCodingException {
                super(wrap);
                assert wrap.getOpCode() == OpCode.Close;
                if (wrap.getBinaryPayload().length >= 2) {
                    this._closeCode = CloseCode.find((wrap.getBinaryPayload()[0] & 0xFF) << 8 | wrap.getBinaryPayload()[1] & 0xFF);
                    this._closeReason = binary2Text(getBinaryPayload(), 2, getBinaryPayload().length - 2);
                }
            }

            public CloseCode getCloseCode() {
                return this._closeCode;
            }

            public String getCloseReason() {
                return this._closeReason;
            }
        }

        public static enum OpCode {
            Continuation(0),
            Text(1),
            Binary(2),
            Close(8),
            Ping(9),
            Pong(10);

            public static NanoWSD.WebSocketFrame.OpCode find(byte value) {
                for (NanoWSD.WebSocketFrame.OpCode opcode : values()) {
                    if (opcode.getValue() == value) {
                        return opcode;
                    }
                }
                return null;
            }

            private final byte code;

            private OpCode(int code) {
                this.code = (byte) code;
            }

            public byte getValue() {
                return this.code;
            }

            public boolean isControlFrame() {
                return this == Close || this == Ping || this == Pong;
            }
        }

        public static final Charset TEXT_CHARSET = Charset.forName("UTF-8");

        public static String binary2Text(byte[] payload) throws CharacterCodingException {
            return new String(payload, NanoWSD.WebSocketFrame.TEXT_CHARSET);
        }

        public static String binary2Text(byte[] payload, int offset, int length) throws CharacterCodingException {
            return new String(payload, offset, length, NanoWSD.WebSocketFrame.TEXT_CHARSET);
        }

        private static int checkedRead(int read) throws IOException {
            if (read < 0) {
                throw new EOFException();
            }
            return read;
        }

        public static NanoWSD.WebSocketFrame read(InputStream in) throws IOException {
            byte head = (byte) checkedRead(in.read());
            boolean fin = (head & 0x80) != 0;
            NanoWSD.WebSocketFrame.OpCode opCode = NanoWSD.WebSocketFrame.OpCode.find((byte) (head & 0x0F));
            if ((head & 0x70) != 0) {
                throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.ProtocolError, "The reserved bits (" + Integer.toBinaryString(head & 0x70) + ") must be 0.");
            }
            if (opCode == null) {
                throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.ProtocolError, "Received frame with reserved/unknown opcode " + (head & 0x0F) + ".");
            } else if (opCode.isControlFrame() && !fin) {
                throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.ProtocolError, "Fragmented control frame.");
            }

            NanoWSD.WebSocketFrame frame = new NanoWSD.WebSocketFrame(opCode, fin);
            frame.readPayloadInfo(in);
            frame.readPayload(in);
            if (frame.getOpCode() == NanoWSD.WebSocketFrame.OpCode.Close) {
                return new NanoWSD.WebSocketFrame.CloseFrame(frame);
            } else {
                return frame;
            }
        }

        public static byte[] text2Binary(String payload) throws CharacterCodingException {
            return payload.getBytes(NanoWSD.WebSocketFrame.TEXT_CHARSET);
        }

        private NanoWSD.WebSocketFrame.OpCode opCode;

        private boolean fin;

        private byte[] maskingKey;

        private byte[] payload;

        // --------------------------------GETTERS---------------------------------

        private transient int _payloadLength;

        private transient String _payloadString;

        private WebSocketFrame(NanoWSD.WebSocketFrame.OpCode opCode, boolean fin) {
            setOpCode(opCode);
            setFin(fin);
        }

        public WebSocketFrame(NanoWSD.WebSocketFrame.OpCode opCode, boolean fin, byte[] payload) {
            this(opCode, fin, payload, null);
        }

        public WebSocketFrame(NanoWSD.WebSocketFrame.OpCode opCode, boolean fin, byte[] payload, byte[] maskingKey) {
            this(opCode, fin);
            setMaskingKey(maskingKey);
            setBinaryPayload(payload);
        }

        public WebSocketFrame(NanoWSD.WebSocketFrame.OpCode opCode, boolean fin, String payload) throws CharacterCodingException {
            this(opCode, fin, payload, null);
        }

        public WebSocketFrame(NanoWSD.WebSocketFrame.OpCode opCode, boolean fin, String payload, byte[] maskingKey) throws CharacterCodingException {
            this(opCode, fin);
            setMaskingKey(maskingKey);
            setTextPayload(payload);
        }

        public WebSocketFrame(NanoWSD.WebSocketFrame.OpCode opCode, List<NanoWSD.WebSocketFrame> fragments) throws NanoWSD.WebSocketException {
            setOpCode(opCode);
            setFin(true);

            long _payloadLength = 0;
            for (NanoWSD.WebSocketFrame inter : fragments) {
                _payloadLength += inter.getBinaryPayload().length;
            }
            if (_payloadLength < 0 || _payloadLength > Integer.MAX_VALUE) {
                throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.MessageTooBig, "Max frame length has been exceeded.");
            }
            this._payloadLength = (int) _payloadLength;
            byte[] payload = new byte[this._payloadLength];
            int offset = 0;
            for (NanoWSD.WebSocketFrame inter : fragments) {
                System.arraycopy(inter.getBinaryPayload(), 0, payload, offset, inter.getBinaryPayload().length);
                offset += inter.getBinaryPayload().length;
            }
            setBinaryPayload(payload);
        }

        public WebSocketFrame(NanoWSD.WebSocketFrame clone) {
            setOpCode(clone.getOpCode());
            setFin(clone.isFin());
            setBinaryPayload(clone.getBinaryPayload());
            setMaskingKey(clone.getMaskingKey());
        }

        public byte[] getBinaryPayload() {
            return this.payload;
        }

        public byte[] getMaskingKey() {
            return this.maskingKey;
        }

        public NanoWSD.WebSocketFrame.OpCode getOpCode() {
            return this.opCode;
        }

        // --------------------------------SERIALIZATION---------------------------

        public String getTextPayload() {
            if (this._payloadString == null) {
                try {
                    this._payloadString = binary2Text(getBinaryPayload());
                } catch (CharacterCodingException e) {
                    throw new RuntimeException("Undetected CharacterCodingException", e);
                }
            }
            return this._payloadString;
        }

        public boolean isFin() {
            return this.fin;
        }

        public boolean isMasked() {
            return this.maskingKey != null && this.maskingKey.length == 4;
        }

        private String payloadToString() {
            if (this.payload == null) {
                return "null";
            } else {
                final StringBuilder sb = new StringBuilder();
                sb.append('[').append(this.payload.length).append("b] ");
                if (getOpCode() == NanoWSD.WebSocketFrame.OpCode.Text) {
                    String text = getTextPayload();
                    if (text.length() > 100) {
                        sb.append(text.substring(0, 100)).append("...");
                    } else {
                        sb.append(text);
                    }
                } else {
                    sb.append("0x");
                    for (int i = 0; i < Math.min(this.payload.length, 50); ++i) {
                        sb.append(Integer.toHexString(this.payload[i] & 0xFF));
                    }
                    if (this.payload.length > 50) {
                        sb.append("...");
                    }
                }
                return sb.toString();
            }
        }

        private void readPayload(InputStream in) throws IOException {
            this.payload = new byte[this._payloadLength];
            int read = 0;
            while (read < this._payloadLength) {
                read += checkedRead(in.read(this.payload, read, this._payloadLength - read));
            }

            if (isMasked()) {
                for (int i = 0; i < this.payload.length; i++) {
                    this.payload[i] ^= this.maskingKey[i % 4];
                }
            }

            // Test for Unicode errors
            if (getOpCode() == NanoWSD.WebSocketFrame.OpCode.Text) {
                this._payloadString = binary2Text(getBinaryPayload());
            }
        }

        // --------------------------------ENCODING--------------------------------

        private void readPayloadInfo(InputStream in) throws IOException {
            byte b = (byte) checkedRead(in.read());
            boolean masked = (b & 0x80) != 0;

            this._payloadLength = (byte) (0x7F & b);
            if (this._payloadLength == 126) {
                // checkedRead must return int for this to work
                this._payloadLength = (checkedRead(in.read()) << 8 | checkedRead(in.read())) & 0xFFFF;
                if (this._payloadLength < 126) {
                    throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.ProtocolError, "Invalid data frame 2byte length. (not using minimal length encoding)");
                }
            } else if (this._payloadLength == 127) {
                long _payloadLength =
                        (long) checkedRead(in.read()) << 56 | (long) checkedRead(in.read()) << 48 | (long) checkedRead(in.read()) << 40 | (long) checkedRead(in.read()) << 32
                                | checkedRead(in.read()) << 24 | checkedRead(in.read()) << 16 | checkedRead(in.read()) << 8 | checkedRead(in.read());
                if (_payloadLength < 65536) {
                    throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.ProtocolError, "Invalid data frame 4byte length. (not using minimal length encoding)");
                }
                if (_payloadLength < 0 || _payloadLength > Integer.MAX_VALUE) {
                    throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.MessageTooBig, "Max frame length has been exceeded.");
                }
                this._payloadLength = (int) _payloadLength;
            }

            if (this.opCode.isControlFrame()) {
                if (this._payloadLength > 125) {
                    throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.ProtocolError, "Control frame with payload length > 125 bytes.");
                }
                if (this.opCode == NanoWSD.WebSocketFrame.OpCode.Close && this._payloadLength == 1) {
                    throw new NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.ProtocolError, "Received close frame with payload len 1.");
                }
            }

            if (masked) {
                this.maskingKey = new byte[4];
                int read = 0;
                while (read < this.maskingKey.length) {
                    read += checkedRead(in.read(this.maskingKey, read, this.maskingKey.length - read));
                }
            }
        }

        public void setBinaryPayload(byte[] payload) {
            this.payload = payload;
            this._payloadLength = payload.length;
            this._payloadString = null;
        }

        public void setFin(boolean fin) {
            this.fin = fin;
        }

        public void setMaskingKey(byte[] maskingKey) {
            if (maskingKey != null && maskingKey.length != 4) {
                throw new IllegalArgumentException("MaskingKey " + Arrays.toString(maskingKey) + " hasn't length 4");
            }
            this.maskingKey = maskingKey;
        }

        public void setOpCode(NanoWSD.WebSocketFrame.OpCode opcode) {
            this.opCode = opcode;
        }

        public void setTextPayload(String payload) throws CharacterCodingException {
            this.payload = text2Binary(payload);
            this._payloadLength = payload.length();
            this._payloadString = payload;
        }

        // --------------------------------CONSTANTS-------------------------------

        public void setUnmasked() {
            setMaskingKey(null);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("WS[");
            sb.append(getOpCode());
            sb.append(", ").append(isFin() ? "fin" : "inter");
            sb.append(", ").append(isMasked() ? "masked" : "unmasked");
            sb.append(", ").append(payloadToString());
            sb.append(']');
            return sb.toString();
        }

        // ------------------------------------------------------------------------

        public void write(OutputStream out) throws IOException {
            byte header = 0;
            if (this.fin) {
                header |= 0x80;
            }
            header |= this.opCode.getValue() & 0x0F;
            out.write(header);

            this._payloadLength = getBinaryPayload().length;
            if (this._payloadLength <= 125) {
                out.write(isMasked() ? 0x80 | (byte) this._payloadLength : (byte) this._payloadLength);
            } else if (this._payloadLength <= 0xFFFF) {
                out.write(isMasked() ? 0xFE : 126);
                out.write(this._payloadLength >>> 8);
                out.write(this._payloadLength);
            } else {
                out.write(isMasked() ? 0xFF : 127);
                out.write(this._payloadLength >>> 56 & 0); // integer only
                // contains
                // 31 bit
                out.write(this._payloadLength >>> 48 & 0);
                out.write(this._payloadLength >>> 40 & 0);
                out.write(this._payloadLength >>> 32 & 0);
                out.write(this._payloadLength >>> 24);
                out.write(this._payloadLength >>> 16);
                out.write(this._payloadLength >>> 8);
                out.write(this._payloadLength);
            }

            if (isMasked()) {
                out.write(this.maskingKey);
                for (int i = 0; i < this._payloadLength; i++) {
                    out.write(getBinaryPayload()[i] ^ this.maskingKey[i % 4]);
                }
            } else {
                out.write(getBinaryPayload());
            }
            out.flush();
        }
    }

    /**
     * logger to log to.
     */
    private static final Logger LOG = Logger.getLogger(NanoWSD.class.getName());

    public static final String HEADER_UPGRADE = "upgrade";

    public static final String HEADER_UPGRADE_VALUE = "websocket";

    public static final String HEADER_CONNECTION = "connection";

    public static final String HEADER_CONNECTION_VALUE = "Upgrade";

    public static final String HEADER_WEBSOCKET_VERSION = "sec-websocket-version";

    public static final String HEADER_WEBSOCKET_VERSION_VALUE = "13";

    public static final String HEADER_WEBSOCKET_KEY = "sec-websocket-key";

    public static final String HEADER_WEBSOCKET_ACCEPT = "sec-websocket-accept";

    public static final String HEADER_WEBSOCKET_PROTOCOL = "sec-websocket-protocol";

    private final static String WEBSOCKET_KEY_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final static char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /**
     * Translates the specified byte array into Base64 string.
     * <p>
     * Android has android.util.Base64, sun has sun.misc.Base64Encoder, Java 8
     * hast java.util.Base64, I have this from stackoverflow:
     * http://stackoverflow.com/a/4265472
     * </p>
     *
     * @param buf
     *            the byte array (not null)
     * @return the translated Base64 string (not null)
     */
    private static String encodeBase64(byte[] buf) {
        int size = buf.length;
        char[] ar = new char[(size + 2) / 3 * 4];
        int a = 0;
        int i = 0;
        while (i < size) {
            byte b0 = buf[i++];
            byte b1 = i < size ? buf[i++] : 0;
            byte b2 = i < size ? buf[i++] : 0;

            int mask = 0x3F;
            ar[a++] = NanoWSD.ALPHABET[b0 >> 2 & mask];
            ar[a++] = NanoWSD.ALPHABET[(b0 << 4 | (b1 & 0xFF) >> 4) & mask];
            ar[a++] = NanoWSD.ALPHABET[(b1 << 2 | (b2 & 0xFF) >> 6) & mask];
            ar[a++] = NanoWSD.ALPHABET[b2 & mask];
        }
        switch (size % 3) {
            case 1:
                ar[--a] = '=';
            case 2:
                ar[--a] = '=';
        }
        return new String(ar);
    }

    public static String makeAcceptKey(String key) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        String text = key + NanoWSD.WEBSOCKET_KEY_MAGIC;
        md.update(text.getBytes(), 0, text.length());
        byte[] sha1hash = md.digest();
        return encodeBase64(sha1hash);
    }

    public NanoWSD(int port) {
        super(port);
    }

    public NanoWSD(String hostname, int port) {
        super(hostname, port);
    }

    private boolean isWebSocketConnectionHeader(Map<String, String> headers) {
        String connection = headers.get(NanoWSD.HEADER_CONNECTION);
        return connection != null && connection.toLowerCase().contains(NanoWSD.HEADER_CONNECTION_VALUE.toLowerCase());
    }

    protected boolean isWebsocketRequested(IHTTPSession session) {
        Map<String, String> headers = session.getHeaders();
        String upgrade = headers.get(NanoWSD.HEADER_UPGRADE);
        boolean isCorrectConnection = isWebSocketConnectionHeader(headers);
        boolean isUpgrade = NanoWSD.HEADER_UPGRADE_VALUE.equalsIgnoreCase(upgrade);
        return isUpgrade && isCorrectConnection;
    }

    // --------------------------------Listener--------------------------------

    protected abstract NanoWSD.WebSocket openWebSocket(IHTTPSession handshake);

    @Override
    public Response serve(final IHTTPSession session) {
        Map<String, String> headers = session.getHeaders();
        if (isWebsocketRequested(session)) {
            if (!NanoWSD.HEADER_WEBSOCKET_VERSION_VALUE.equalsIgnoreCase(headers.get(NanoWSD.HEADER_WEBSOCKET_VERSION))) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT,
                        "Invalid Websocket-Version " + headers.get(NanoWSD.HEADER_WEBSOCKET_VERSION));
            }

            if (!headers.containsKey(NanoWSD.HEADER_WEBSOCKET_KEY)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "Missing Websocket-Key");
            }

            NanoWSD.WebSocket webSocket = openWebSocket(session);
            Response handshakeResponse = webSocket.getHandshakeResponse();
            try {
                handshakeResponse.addHeader(NanoWSD.HEADER_WEBSOCKET_ACCEPT, makeAcceptKey(headers.get(NanoWSD.HEADER_WEBSOCKET_KEY)));
            } catch (NoSuchAlgorithmException e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT,
                        "The SHA-1 Algorithm required for websockets is not available on the server.");
            }

            if (headers.containsKey(NanoWSD.HEADER_WEBSOCKET_PROTOCOL)) {
                handshakeResponse.addHeader(NanoWSD.HEADER_WEBSOCKET_PROTOCOL, headers.get(NanoWSD.HEADER_WEBSOCKET_PROTOCOL).split(",")[0]);
            }

            return handshakeResponse;
        } else {
            return serveHttp(session);
        }
    }

    protected Response serveHttp(final IHTTPSession session) {
        return super.serve(session);
    }

    /**
     * not all websockets implementations accept gzip compression.
     */
    @Override
    protected boolean useGzipWhenAccepted(Response r) {
        return false;
    }
}

