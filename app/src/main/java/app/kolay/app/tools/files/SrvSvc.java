package app.kolay.app.tools.files;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;
import com.hierynomus.smbj.share.Share;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The server's share list: one DCE/RPC call, srvsvc NetrShareEnum (level 1), over the IPC$ named pipe. smbj has no RPC layer and
 * this is the only call needed, so the few dozen bytes of NDR are written by hand. Every length read from the reply is bounds-checked.
 */
final class SrvSvc {
    private SrvSvc() {}

    private static final int FRAG = 4280;
    private static final byte[] SRVSVC = uuid("4b324fc8-1670-01d3-1278-5a47bf6ee188"), NDR = uuid("8a885d04-1ceb-11c9-9fe8-08002b104860");

    /** Disk shares, without hidden ("$") and administrative ones. */
    static List<String> diskShares(Session s, String host) throws IOException {
        Share sh = s.connectShare("IPC$");
        try {
            if (!(sh instanceof PipeShare)) throw new IOException("IPC$ yok");
            try (NamedPipe p = ((PipeShare) sh).open("srvsvc", SMB2ImpersonationLevel.Impersonation, EnumSet.of(AccessMask.MAXIMUM_ALLOWED), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE), SMB2CreateDisposition.FILE_OPEN, null)) {
                checkAck(call(p, bind()));
                return parse(call(p, request(host)));
            }
        } finally {
            try { sh.close(); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- PDUs

    private static ByteBuffer pdu(int type, int len) {
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 5).put((byte) 0).put((byte) type).put((byte) 3);   // v5.0, first+last fragment
        b.putInt(0x10);                                                 // little-endian, ASCII, IEEE float
        b.putShort((short) len).putShort((short) 0).putInt(1);          // frag length, auth length, call id
        return b;
    }

    private static byte[] bind() {
        ByteBuffer b = pdu(11, 72);
        b.putShort((short) FRAG).putShort((short) FRAG).putInt(0);
        b.put((byte) 1).put((byte) 0).putShort((short) 0);              // one context
        b.putShort((short) 0).put((byte) 1).put((byte) 0);              // context 0, one transfer syntax
        b.put(SRVSVC).putShort((short) 3).putShort((short) 0);
        b.put(NDR).putInt(2);
        return b.array();
    }

    private static byte[] request(String host) {
        ByteBuffer stub = ByteBuffer.allocate(512 + host.length() * 2).order(ByteOrder.LITTLE_ENDIAN);
        String server = "\\\\" + host;
        stub.putInt(0x20000);                                           // ServerName: unique pointer + conformant varying string
        int n = server.length() + 1;
        stub.putInt(n).putInt(0).putInt(n);
        stub.put((server + '\0').getBytes(StandardCharsets.UTF_16LE));
        while (stub.position() % 4 != 0) stub.put((byte) 0);
        stub.putInt(1).putInt(1).putInt(0x20004).putInt(0).putInt(0);  // level 1, union tag 1, container {0 entries, null buffer}
        stub.putInt(0xFFFFFFFF);                                        // preferred maximum length: everything
        stub.putInt(0x20008).putInt(0);                                 // resume handle
        int sl = stub.position();
        ByteBuffer b = pdu(0, 24 + sl);
        b.putInt(sl).putShort((short) 0).putShort((short) 15);          // alloc hint, context 0, opnum 15 = NetrShareEnum
        b.put(stub.array(), 0, sl);
        return b.array();
    }

    /** Sends one PDU and returns the reply's body (fragments joined); bind_ack body for a bind, stub data for a request. */
    private static byte[] call(NamedPipe p, byte[] pdu) throws IOException {
        byte[] first = new byte[FRAG + 64];
        int n = p.transact(pdu, first);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buf = first; int len = n;
        for (int frags = 0; frags < 4096; frags++) {
            if (len < 16) throw new IOException("srvsvc: kısa yanıt");
            ByteBuffer h = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN);
            int type = buf[2] & 0xff, flags = buf[3] & 0xff;
            int flen = h.getShort(8) & 0xffff, alen = h.getShort(10) & 0xffff;
            if (flen > len || flen < 16) throw new IOException("srvsvc: bozuk yanıt");
            if (type == 3) throw new IOException(String.format("srvsvc hatası 0x%08X", flen >= 28 ? h.getInt(24) : 0));
            if (type == 13) throw new IOException("srvsvc: bağlanma reddedildi");
            if (type == 12) { body.write(buf, 16, flen - 16); return body.toByteArray(); }
            if (type != 2 || flen < 24) throw new IOException("srvsvc: beklenmeyen yanıt");
            int end = flen - (alen > 0 ? alen + 8 : 0);
            if (end < 24) throw new IOException("srvsvc: bozuk yanıt");
            body.write(buf, 24, end - 24);
            if ((flags & 2) != 0) return body.toByteArray();
            buf = new byte[FRAG + 64];
            len = p.read(buf);
        }
        throw new IOException("srvsvc: yanıt çok uzun");
    }

    // ---------------------------------------------------------------- reply

    /** bind_ack body (after the 16-byte header): the first presentation result must be "acceptance". */
    private static void checkAck(byte[] a) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
        if (a.length < 10) throw new IOException("srvsvc: kısa yanıt");
        int sec = b.getShort(8) & 0xffff;
        int at = 10 + sec;
        while ((at + 16) % 4 != 0) at++;   // aligned relative to the PDU start
        if (a.length < at + 6) throw new IOException("srvsvc: kısa yanıt");
        if ((a[at] & 0xff) < 1 || b.getShort(at + 4) != 0) throw new IOException("srvsvc: bağlanma reddedildi");
    }

    static List<String> parse(byte[] stub) throws IOException {
        Ndr r = new Ndr(stub);
        r.u32(); r.u32();                                // level, union tag
        List<String> out = new ArrayList<>();
        if (r.u32() == 0) return out;                    // container pointer
        int count = r.u32();
        if (r.u32() == 0) return out;                    // buffer pointer
        int max = r.u32();
        if (count < 0 || count > max || count > 10000) throw new IOException("srvsvc: bozuk liste");
        int[] name = new int[count], type = new int[count], remark = new int[count];
        for (int i = 0; i < count; i++) { name[i] = r.u32(); type[i] = r.u32(); remark[i] = r.u32(); }
        for (int i = 0; i < count; i++) {
            String n = name[i] != 0 ? r.str() : null;
            if (remark[i] != 0) r.str();
            boolean disk = (type[i] & 0x0FFFFFFF) == 0, special = (type[i] & 0x80000000) != 0;
            if (n != null && disk && !special && !n.endsWith("$") && !n.isEmpty()) out.add(n);
        }
        return out;
    }

    private static final class Ndr {
        private final ByteBuffer b;
        Ndr(byte[] a) { b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN); }

        int u32() throws IOException {
            while (b.position() % 4 != 0) skip(1);
            if (b.remaining() < 4) throw new IOException("srvsvc: kısa yanıt");
            return b.getInt();
        }

        String str() throws IOException {
            int max = u32(), off = u32(), actual = u32();
            if (actual < 0 || off != 0 || actual > max || actual > 4096 || b.remaining() < actual * 2) throw new IOException("srvsvc: bozuk ad");
            byte[] s = new byte[actual * 2];
            b.get(s);
            String v = new String(s, StandardCharsets.UTF_16LE);
            int z = v.indexOf('\0');
            return z >= 0 ? v.substring(0, z) : v;
        }

        private void skip(int n) throws IOException {
            if (b.remaining() < n) throw new IOException("srvsvc: kısa yanıt");
            b.position(b.position() + n);
        }
    }

    /** RPC wire form of a UUID: the first three fields little-endian, the rest as written. */
    private static byte[] uuid(String s) {
        String h = s.replace("-", "");
        byte[] raw = new byte[16];
        for (int i = 0; i < 16; i++) raw[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        byte[] o = raw.clone();
        o[0] = raw[3]; o[1] = raw[2]; o[2] = raw[1]; o[3] = raw[0];
        o[4] = raw[5]; o[5] = raw[4]; o[6] = raw[7]; o[7] = raw[6];
        return o;
    }
}
