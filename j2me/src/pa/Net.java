package pa;

import java.util.Vector;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.DiscoveryListener;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.RemoteDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

// JSR-82 networking. Host: btspp server (accept). Client: inquiry + service search.
public class Net implements Runnable, DiscoveryListener {
    public static final String SVC_UUID = "AABBCCDDEEFF00112233445566778899";
    public static final String SVC_NAME = "PortalArena";

    public static final int OFF = 0, HOSTING = 1, DISCOVERING = 2, CONNECTING = 3, ONLINE = 4, FAILED = 5;

    public int state = OFF;
    public String error = "";
    public boolean isHost = false;

    public final Vector devList = new Vector();   // RemoteDevice
    public final Vector nameList = new Vector();  // String

    LocalDevice local;
    DiscoveryAgent agent;
    StreamConnectionNotifier notifier;
    StreamConnection conn;
    DataInputStream in;
    DataOutputStream out;
    boolean running = true;

    // shared with game thread
    public int rx, ry, rangle, rhp = 100, rflags;
    public long rlast;
    public int pendingDmg;
    public boolean remoteDied;

    public void host() {
        try {
            isHost = true;
            state = HOSTING;
            local = LocalDevice.getLocalDevice();
            try { local.setDiscoverable(DiscoveryAgent.GIAC); } catch (Exception e) { }
            new Thread(this).start();
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    public void discover() {
        try {
            isHost = false;
            state = DISCOVERING;
            devList.removeAllElements();
            nameList.removeAllElements();
            local = LocalDevice.getLocalDevice();
            agent = local.getDiscoveryAgent();
            if (!agent.startInquiry(DiscoveryAgent.GIAC, this)) fail("Inquiry not started");
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    public void connect(int idx) {
        try {
            state = CONNECTING;
            RemoteDevice dev = (RemoteDevice) devList.elementAt(idx);
            UUID[] uuids = new UUID[] { new UUID(SVC_UUID, false) };
            agent.searchServices(null, uuids, dev, this);
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    public void cancel() {
        try { if (agent != null) agent.cancelInquiry(this); } catch (Exception e) { }
        close();
        state = OFF;
    }

    void fail(String msg) {
        error = msg;
        state = FAILED;
        close();
    }

    public void close() {
        running = false;
        try { if (conn != null) conn.close(); } catch (Exception e) { }
        conn = null;
        in = null;
        out = null;
    }

    public void run() {
        try {
            String url = "btspp://localhost:" + SVC_UUID + ";name=" + SVC_NAME;
            notifier = (StreamConnectionNotifier) Connector.open(url);
            conn = notifier.acceptAndOpen();
            openStreams();
        } catch (Exception e) {
            if (state != OFF) fail(e.toString());
        }
    }

    void openStreams() throws IOException {
        in = conn.openDataInputStream();
        out = conn.openDataOutputStream();
        state = ONLINE;
        rlast = System.currentTimeMillis();
        running = true;
        new Thread(new Reader()).start();
    }

    public synchronized void sendState(int x, int y, int a, int hp, int flags) {
        try {
            if (out == null || state != ONLINE) return;
            out.writeByte('S');
            out.writeShort(x);
            out.writeShort(y);
            out.writeByte(a);
            out.writeByte(hp);
            out.writeByte(flags);
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    public synchronized void sendHit(int dmg) {
        try {
            if (out == null || state != ONLINE) return;
            out.writeByte('H');
            out.writeByte(dmg);
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    public synchronized void sendDeath() {
        try {
            if (out == null || state != ONLINE) return;
            out.writeByte('D');
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    class Reader implements Runnable {
        public void run() {
            try {
                while (running) {
                    int t = in.readByte();
                    if (t == 'S') {
                        int x = in.readShort();
                        int y = in.readShort();
                        int a = in.readByte();
                        int h = in.readByte();
                        int f = in.readByte();
                        synchronized (Net.this) {
                            rx = x; ry = y; rangle = a & 255; rhp = h; rflags = f;
                            rlast = System.currentTimeMillis();
                        }
                    } else if (t == 'H') {
                        int d = in.readByte();
                        synchronized (Net.this) { pendingDmg += d; }
                    } else if (t == 'D') {
                        synchronized (Net.this) { remoteDied = true; }
                    }
                }
            } catch (Exception e) {
                if (running) fail("Link lost: " + e.toString());
            }
        }
    }

    // ---- DiscoveryListener ----
    public void deviceDiscovered(RemoteDevice dev, javax.bluetooth.DeviceClass dc) {
        if (!devList.contains(dev)) {
            String name;
            try { name = dev.getFriendlyName(false); } catch (Exception e) { name = dev.getBluetoothAddress(); }
            devList.addElement(dev);
            nameList.addElement(name == null ? "?" : name);
        }
    }

    public void inquiryCompleted(int discType) { }

    public void servicesDiscovered(int transID, ServiceRecord[] records) {
        if (records != null && records.length > 0 && state == CONNECTING) {
            try { agent.cancelServiceSearch(transID); } catch (Exception e) { }
            String url = records[0].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            try {
                conn = (StreamConnection) Connector.open(url);
                openStreams();
            } catch (Exception e) {
                fail(e.toString());
            }
        }
    }

    public void serviceSearchCompleted(int transID, int respCode) {
        if (state == CONNECTING) fail("Service not found");
    }
}
