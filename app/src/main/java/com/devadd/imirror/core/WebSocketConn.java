package com.devadd.imirror.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Minimal RFC 6455 framing for one connection. Client frames must be masked. */
final class WebSocketConn {
    static final class Message {
        final boolean binary;
        final byte[] data;
        Message(boolean binary, byte[] data) { this.binary = binary; this.data = data; }
    }

    private final DataInputStream in;
    private final OutputStream out;
    private volatile int maxPayload;

    WebSocketConn(InputStream in, OutputStream out, int maxPayload) {
        this.in = new DataInputStream(in);
        this.out = out;
        this.maxPayload = maxPayload;
    }

    void setMaxPayload(int n) { maxPayload = n; }

    /** Next text or binary message, or null once the peer closed. Answers pings itself. */
    Message read() throws IOException {
        ByteArrayOutputStream partial = null;
        boolean partialBinary = false;
        while (true) {
            int b0;
            try { b0 = in.readUnsignedByte(); } catch (EOFException e) { return null; }
            int b1 = in.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            if ((b0 & 0x70) != 0) throw new IOException("reserved bits");
            int op = b0 & 0x0f;
            if ((b1 & 0x80) == 0) throw new IOException("unmasked client frame");
            long len = b1 & 0x7f;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) { len = in.readLong(); if (len < 0) throw new IOException("bad length"); }
            if (len > maxPayload) throw new IOException("frame too large");
            byte[] mask = new byte[4];
            in.readFully(mask);
            byte[] data = new byte[(int) len];
            in.readFully(data);
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];

            if (op >= 8) {                                   // control frames
                if (!fin || len > 125) throw new IOException("bad control frame");
                if (op == 8) { try { sendFrame(8, data); } catch (IOException ignored) { } return null; }
                if (op == 9) sendFrame(10, data);
                else if (op != 10) throw new IOException("bad opcode");
                continue;
            }
            if (op == 0) {
                if (partial == null) throw new IOException("unexpected continuation");
                if (partial.size() + data.length > maxPayload) throw new IOException("message too large");
                partial.write(data);
                if (fin) return new Message(partialBinary, partial.toByteArray());
            } else if (op == 1 || op == 2) {
                if (partial != null) throw new IOException("interleaved data frames");
                if (fin) return new Message(op == 2, data);
                partial = new ByteArrayOutputStream();
                partial.write(data);
                partialBinary = op == 2;
            } else {
                throw new IOException("bad opcode");
            }
        }
    }

    void sendText(String s) throws IOException { sendFrame(1, s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    void sendBinary(byte[] d) throws IOException { sendFrame(2, d); }
    void sendClose() { try { sendFrame(8, new byte[] {0x03, (byte) 0xe8}); } catch (IOException ignored) { } }

    private synchronized void sendFrame(int op, byte[] data) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream(10);
        h.write(0x80 | op);
        if (data.length < 126) h.write(data.length);
        else if (data.length < 65536) { h.write(126); h.write(data.length >> 8); h.write(data.length); }
        else {
            h.write(127);
            for (int i = 7; i >= 0; i--) h.write((int) (((long) data.length >> (8 * i)) & 0xff));
        }
        out.write(h.toByteArray());
        out.write(data);
        out.flush();
    }
}
