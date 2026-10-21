package pa;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;

public class PA extends MIDlet {
    Game g;

    public void startApp() {
        if (g == null) {
            g = new Game(this);
            Display.getDisplay(this).setCurrent(g);
            new Thread(g).start();
        }
    }

    public void pauseApp() { }

    public void destroyApp(boolean unconditional) { }
}
