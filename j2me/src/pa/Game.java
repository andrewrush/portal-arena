package pa;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.GameCanvas;
import javax.microedition.lcdui.Graphics;

public class Game extends GameCanvas implements Runnable {
    static final int ST_MENU = 0, ST_WAIT = 1, ST_DISC = 2, ST_GAME = 4;
    static final int K_UP = 1, K_DOWN = 2, K_LEFT = 4, K_RIGHT = 8, K_FIRE = 16, K_SL = 32, K_SR = 64;

    PA app;
    Net net;
    Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    Font large = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);

    int state = ST_MENU;
    int menuSel = 0, discSel = 0;
    int keys = 0, prevKeys = 0;
    boolean softL = false, softR = false, prevSoftL = false, prevSoftR = false;
    String status = "";

    // player
    int px, py, pang, sector;
    int hp = 100;
    boolean alive = true;
    long respawnAt;
    int myKills, myDeaths;
    long fireReady, muzzleUntil, hurtUntil, msgUntil, lastSend;
    String msg = "";
    final int[] mv = new int[3];

    // remote (lerped)
    boolean remoteInit = false;
    int ddx, ddy;

    static final int SPAWN_X1 = 4 * 256, SPAWN_Y1 = 4 * 256;    // host
    static final int SPAWN_X2 = 20 * 256, SPAWN_Y2 = 20 * 256;  // client

    public Game(PA app) {
        super(false);
        this.app = app;
        setFullScreenMode(true);
        net = new Net();
    }

    public void run() {
        while (true) {
            long t0 = System.currentTimeMillis();
            try {
                update();
                paint();
            } catch (Throwable e) {
                status = "ERR " + e.toString();
            }
            long dt = System.currentTimeMillis() - t0;
            if (dt < 30) {
                try { Thread.sleep(30 - dt); } catch (InterruptedException e) { }
            }
        }
    }

    // ---------- input ----------
    protected void keyPressed(int k) { setKey(k, true); }
    protected void keyReleased(int k) { setKey(k, false); }

    void toggle(int bit, boolean on) {
        keys = on ? (keys | bit) : (keys & ~bit);
    }

    void setKey(int k, boolean on) {
        int ga = 0;
        try { ga = getGameAction(k); } catch (Exception e) { }
        if (ga == UP || k == KEY_NUM2) toggle(K_UP, on);
        else if (ga == DOWN || k == KEY_NUM8) toggle(K_DOWN, on);
        else if (ga == LEFT || k == KEY_NUM4) toggle(K_LEFT, on);
        else if (ga == RIGHT || k == KEY_NUM6) toggle(K_RIGHT, on);
        else if (ga == FIRE || k == KEY_NUM5) toggle(K_FIRE, on);
        else if (k == KEY_NUM1 || k == KEY_NUM7) toggle(K_SL, on);
        else if (k == KEY_NUM3 || k == KEY_NUM9) toggle(K_SR, on);
        else if (ga == GAME_A || k == -6) softL = on;
        else if (ga == GAME_B || k == -7) softR = on;
    }

    // ---------- update ----------
    void update() {
        long now = System.currentTimeMillis();
        int pressed = keys & ~prevKeys;
        boolean softLEdge = softL && !prevSoftL;
        boolean softREdge = softR && !prevSoftR;

        if (net.state == Net.FAILED) {
            status = net.error;
            state = ST_MENU;
            net.state = Net.OFF;
        }

        switch (state) {
            case ST_MENU: {
                if ((pressed & K_UP) != 0) menuSel = (menuSel + 2) % 3;
                if ((pressed & K_DOWN) != 0) menuSel = (menuSel + 1) % 3;
                if ((pressed & K_FIRE) != 0) {
                    if (menuSel == 0) { status = ""; net.host(); state = ST_WAIT; }
                    else if (menuSel == 1) { status = ""; net.discover(); state = ST_DISC; discSel = 0; }
                    else { app.notifyDestroyed(); }
                }
                break;
            }
            case ST_WAIT: {
                if (net.state == Net.ONLINE) startGame(true);
                if (softREdge || softLEdge) { net.cancel(); state = ST_MENU; }
                break;
            }
            case ST_DISC: {
                int n = net.nameList.size();
                if (n > 0) {
                    if ((pressed & K_UP) != 0) discSel = (discSel + n - 1) % n;
                    if ((pressed & K_DOWN) != 0) discSel = (discSel + 1) % n;
                    if ((pressed & K_FIRE) != 0) net.connect(discSel);
                }
                if (net.state == Net.ONLINE) startGame(false);
                if (softREdge) { net.cancel(); state = ST_MENU; }
                break;
            }
            case ST_GAME: {
                updateGame(now);
                if (softLEdge) {
                    net.close();
                    net.state = Net.OFF;
                    state = ST_MENU;
                }
                break;
            }
        }

        prevKeys = keys;
        prevSoftL = softL;
        prevSoftR = softR;
    }

    void startGame(boolean host) {
        if (host) { px = SPAWN_X1; py = SPAWN_Y1; pang = 0; }
        else { px = SPAWN_X2; py = SPAWN_Y2; pang = 128; }
        sector = Map.sectorAt(px, py);
        hp = 100; alive = true; myKills = 0; myDeaths = 0;
        remoteInit = false; lastSend = 0; msg = "";
        state = ST_GAME;
    }

    void respawn() {
        boolean host = net.isHost;
        if (host) { px = SPAWN_X1; py = SPAWN_Y1; pang = 0; }
        else { px = SPAWN_X2; py = SPAWN_Y2; pang = 128; }
        sector = Map.sectorAt(px, py);
        hp = 100; alive = true;
    }

    void tryGo(int nx, int ny) {
        int r = Map.tryMove(px, py, sector, nx, ny, mv);
        if (r == 1) { px = nx; py = ny; }
        else if (r == 2) { px = mv[0]; py = mv[1]; sector = mv[2]; }
    }

    void showMsg(String m) {
        msg = m;
        msgUntil = System.currentTimeMillis() + 1600;
    }

    void updateGame(long now) {
        if (alive) {
            if ((keys & K_LEFT) != 0) pang = (pang - 2) & 255;
            if ((keys & K_RIGHT) != 0) pang = (pang + 2) & 255;
            int c = Fx.cos(pang), s = Fx.sin(pang);
            int fwd = 0, str = 0;
            if ((keys & K_UP) != 0) fwd = 10;
            if ((keys & K_DOWN) != 0) fwd = -10;
            if ((keys & K_SL) != 0) str = -10;
            if ((keys & K_SR) != 0) str = 10;
            if (fwd != 0 || str != 0) {
                int mx = (c * fwd - s * str) >> 8;
                int my = (s * fwd + c * str) >> 8;
                tryGo(px + mx, py);
                tryGo(px, py + my);
            }

            if ((keys & K_FIRE) != 0 && now > fireReady && net.state == Net.ONLINE) {
                fireReady = now + 400;
                muzzleUntil = now + 120;
                net.sendState(px, py, pang, hp, 1);
                lastSend = now;
                int ddx = net.rx - px, ddy = net.ry - py;
                int cos = Fx.cos(pang), sin = Fx.sin(pang);
                int tx = (ddx * cos + ddy * sin) >> 8;
                int ty = (ddy * cos - ddx * sin) >> 8;
                if (tx > Render.NEAR && (ty < 0 ? -ty : ty) * 8 < tx) {
                    if (Map.los(px, py, sector, net.rx, net.ry)) {
                        net.sendHit(25);
                        showMsg("HIT!");
                    }
                }
            }
        }

        synchronized (net) {
            if (net.pendingDmg > 0 && alive) {
                hp -= net.pendingDmg;
                net.pendingDmg = 0;
                hurtUntil = now + 200;
                if (hp <= 0) {
                    hp = 0; alive = false; myDeaths++;
                    respawnAt = now + 2500;
                    net.sendDeath();
                    showMsg("YOU DIED");
                }
            }
            if (net.remoteDied) {
                net.remoteDied = false;
                myKills++;
                showMsg("ENEMY DOWN");
            }
        }

        if (!alive && now > respawnAt) respawn();

        if (now - lastSend > 100) {
            net.sendState(px, py, pang, hp, 0);
            lastSend = now;
        }

        if (!remoteInit && net.rlast > 0) {
            remoteInit = true;
            ddx = net.rx; ddy = net.ry;
        }
        if (remoteInit) {
            ddx += (net.rx - ddx) >> 2;
            ddy += (net.ry - ddy) >> 2;
        }

        if (net.rlast > 0 && now - net.rlast > 3000) {
            status = "Link lost";
            net.close();
            net.state = Net.OFF;
            state = ST_MENU;
        }
    }

    // ---------- paint ----------
    void paint() {
        Graphics g = getGraphics();
        g.setFont(small);
        switch (state) {
            case ST_MENU: paintMenu(g); break;
            case ST_WAIT: paintWait(g); break;
            case ST_DISC: paintDisc(g); break;
            case ST_GAME: paintGame(g, System.currentTimeMillis()); break;
        }
        flushGraphics();
    }

    void paintMenu(Graphics g) {
        g.setColor(0x0A0A14);
        g.fillRect(0, 0, 240, 320);
        g.setFont(large);
        g.setColor(0xFFB020);
        g.drawString("PORTAL", 120, 40, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0x20C0FF);
        g.drawString("ARENA", 120, 70, Graphics.TOP | Graphics.HCENTER);
        g.setFont(small);
        g.setColor(0x808090);
        g.drawString("bt duel  2 x nokia x2-00", 120, 110, Graphics.TOP | Graphics.HCENTER);

        String[] items = { "HOST GAME", "JOIN GAME", "EXIT" };
        for (int i = 0; i < 3; i++) {
            g.setColor(i == menuSel ? 0xFFFF40 : 0xC0C0D0);
            g.drawString((i == menuSel ? "> " : "  ") + items[i], 120, 160 + i * 24,
                         Graphics.TOP | Graphics.HCENTER);
        }
        g.setColor(0x606070);
        g.drawString("2/8 select, 5 ok", 120, 250, Graphics.TOP | Graphics.HCENTER);
        if (status.length() > 0) {
            g.setColor(0xFF5050);
            g.drawString(status, 120, 285, Graphics.TOP | Graphics.HCENTER);
        }
    }

    void paintWait(Graphics g) {
        g.setColor(0x0A0A14);
        g.fillRect(0, 0, 240, 320);
        g.setFont(large);
        g.setColor(0x20C0FF);
        g.drawString("WAITING...", 120, 100, Graphics.TOP | Graphics.HCENTER);
        g.setFont(small);
        g.setColor(0xC0C0D0);
        String addr = "?";
        try { addr = net.local.getBluetoothAddress(); } catch (Exception e) { }
        g.drawString("bt addr: " + addr, 120, 150, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0x606070);
        g.drawString("right soft key: cancel", 120, 260, Graphics.TOP | Graphics.HCENTER);
    }

    void paintDisc(Graphics g) {
        g.setColor(0x0A0A14);
        g.fillRect(0, 0, 240, 320);
        g.setFont(large);
        g.setColor(0x20C0FF);
        g.drawString("JOIN GAME", 120, 30, Graphics.TOP | Graphics.HCENTER);
        g.setFont(small);
        int n = net.nameList.size();
        if (n == 0) {
            g.setColor(0xC0C0D0);
            g.drawString("searching devices...", 120, 110, Graphics.TOP | Graphics.HCENTER);
        } else {
            for (int i = 0; i < n; i++) {
                g.setColor(i == discSel ? 0xFFFF40 : 0xC0C0D0);
                g.drawString((i == discSel ? "> " : "  ") + (String) net.nameList.elementAt(i),
                             20, 90 + i * 24, Graphics.TOP | Graphics.LEFT);
            }
        }
        g.setColor(0x606070);
        String hint = net.state == Net.CONNECTING ? "connecting..." : "5 connect";
        g.drawString(hint, 120, 250, Graphics.TOP | Graphics.HCENTER);
        g.drawString("right soft key: back", 120, 270, Graphics.TOP | Graphics.HCENTER);
        if (status.length() > 0) {
            g.setColor(0xFF5050);
            g.drawString(status, 120, 295, Graphics.TOP | Graphics.HCENTER);
        }
    }

    void paintGame(Graphics g, long now) {
        Render.render(this, g);

        if (remoteInit && net.state == Net.ONLINE && now - net.rlast < 3000) {
            boolean fl = false;
            synchronized (net) { fl = (net.rflags & 1) != 0 && (now - net.rlast < 250); }
            Render.sprite(this, g, ddx, ddy, fl);
        }

        // crosshair
        g.setColor(0x30FF30);
        g.drawRect(117, 157, 6, 6);
        g.fillRect(119, 159, 2, 2);

        // gun
        g.setColor(0x35353F);
        g.fillRect(104, 272, 32, 48);
        g.fillRect(112, 252, 16, 22);
        g.setColor(0x55555F);
        g.fillRect(108, 278, 24, 8);
        if (now < muzzleUntil) {
            g.setColor(0xFFFF90);
            g.fillRect(106, 238, 28, 16);
        }

        // HUD
        g.setColor(0x000000);
        g.fillRect(0, 0, 240, 16);
        g.setColor(0x802020); g.fillRect(4, 5, 60, 7);
        g.setColor(0x20C040); g.fillRect(4, 5, hp * 60 / 100, 7);
        int ehp;
        synchronized (net) { ehp = net.rhp; }
        g.setColor(0x802020); g.fillRect(176, 5, 60, 7);
        g.setColor(0xC03030); g.fillRect(176, 5, ehp * 60 / 100, 7);
        g.setColor(0xFFFFFF);
        g.drawString(" " + hp, 66, 4, Graphics.TOP | Graphics.LEFT);
        g.drawString(ehp + " ", 170, 4, Graphics.TOP | Graphics.RIGHT);
        g.drawString(myKills + " : " + myDeaths, 120, 4, Graphics.TOP | Graphics.HCENTER);
        g.setColor(now - net.rlast < 1500 ? 0x20FF20 : 0xFF2020);
        g.fillRect(148, 17, 4, 4);
        g.setColor(0x606070);
        g.drawString("soft: menu", 4, 306, Graphics.TOP | Graphics.LEFT);

        if (now < msgUntil) {
            g.setFont(large);
            g.setColor(0xFFFF40);
            g.drawString(msg, 120, 118, Graphics.TOP | Graphics.HCENTER);
        }
        if (now < hurtUntil) {
            g.setColor(0xFF0000);
            g.fillRect(0, 0, 240, 5);
            g.fillRect(0, 315, 240, 5);
            g.fillRect(0, 0, 5, 320);
            g.fillRect(235, 0, 5, 320);
        }
        if (!alive) {
            g.setColor(0x500000);
            g.fillRect(0, 120, 240, 80);
            g.setFont(large);
            g.setColor(0xFFFFFF);
            g.drawString("YOU DIED", 120, 138, Graphics.TOP | Graphics.HCENTER);
            g.setFont(small);
            g.drawString("respawning...", 120, 168, Graphics.TOP | Graphics.HCENTER);
        }
    }
}
