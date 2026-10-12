package fr.tibkpl.daikatana;

import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Transmet manette et clavier Android au moteur, via une socket TCP locale vers
 * libdkbridge.so (manette SDL virtuelle). Format : voir android/runtime/dkbridge/dkbridge.c.
 */
final class InputBridge {

    private static final int MSG_BUTTON = 1;
    private static final int MSG_AXIS = 2;
    private static final int MSG_KEY = 3;
    private static final int MSG_PAUSE = 4;

    // SDL_GameControllerButton
    private static final int BTN_A = 0, BTN_B = 1, BTN_X = 2, BTN_Y = 3, BTN_BACK = 4, BTN_GUIDE = 5,
            BTN_START = 6, BTN_LSTICK = 7, BTN_RSTICK = 8, BTN_LSHOULDER = 9, BTN_RSHOULDER = 10,
            BTN_DPAD_UP = 11, BTN_DPAD_DOWN = 12, BTN_DPAD_LEFT = 13, BTN_DPAD_RIGHT = 14;
    // SDL_GameControllerAxis
    private static final int AXIS_LX = 0, AXIS_LY = 1, AXIS_RX = 2, AXIS_RY = 3,
            AXIS_LTRIGGER = 4, AXIS_RTRIGGER = 5;

    private static final SparseIntArray BUTTONS = new SparseIntArray();
    private static final SparseIntArray SCANCODES = new SparseIntArray();

    static {
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_A, BTN_A);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_B, BTN_B);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_X, BTN_X);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_Y, BTN_Y);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_SELECT, BTN_BACK);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_MODE, BTN_GUIDE);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_START, BTN_START);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_THUMBL, BTN_LSTICK);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_THUMBR, BTN_RSTICK);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_L1, BTN_LSHOULDER);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_R1, BTN_RSHOULDER);
        BUTTONS.put(KeyEvent.KEYCODE_DPAD_UP, BTN_DPAD_UP);
        BUTTONS.put(KeyEvent.KEYCODE_DPAD_DOWN, BTN_DPAD_DOWN);
        BUTTONS.put(KeyEvent.KEYCODE_DPAD_LEFT, BTN_DPAD_LEFT);
        BUTTONS.put(KeyEvent.KEYCODE_DPAD_RIGHT, BTN_DPAD_RIGHT);

        // Clavier physique -> SDL_Scancode
        for (int i = 0; i < 26; i++) SCANCODES.put(KeyEvent.KEYCODE_A + i, 4 + i);
        for (int i = 1; i <= 9; i++) SCANCODES.put(KeyEvent.KEYCODE_0 + i, 29 + i);
        SCANCODES.put(KeyEvent.KEYCODE_0, 39);
        SCANCODES.put(KeyEvent.KEYCODE_ENTER, 40);
        SCANCODES.put(KeyEvent.KEYCODE_ESCAPE, 41);
        SCANCODES.put(KeyEvent.KEYCODE_BACK, 41);
        SCANCODES.put(KeyEvent.KEYCODE_DEL, 42);
        SCANCODES.put(KeyEvent.KEYCODE_TAB, 43);
        SCANCODES.put(KeyEvent.KEYCODE_SPACE, 44);
        SCANCODES.put(KeyEvent.KEYCODE_MINUS, 45);
        SCANCODES.put(KeyEvent.KEYCODE_EQUALS, 46);
        SCANCODES.put(KeyEvent.KEYCODE_LEFT_BRACKET, 47);
        SCANCODES.put(KeyEvent.KEYCODE_RIGHT_BRACKET, 48);
        SCANCODES.put(KeyEvent.KEYCODE_BACKSLASH, 49);
        SCANCODES.put(KeyEvent.KEYCODE_SEMICOLON, 51);
        SCANCODES.put(KeyEvent.KEYCODE_APOSTROPHE, 52);
        SCANCODES.put(KeyEvent.KEYCODE_GRAVE, 53);
        SCANCODES.put(KeyEvent.KEYCODE_COMMA, 54);
        SCANCODES.put(KeyEvent.KEYCODE_PERIOD, 55);
        SCANCODES.put(KeyEvent.KEYCODE_SLASH, 56);
        for (int i = 0; i < 12; i++) SCANCODES.put(KeyEvent.KEYCODE_F1 + i, 58 + i);
        SCANCODES.put(KeyEvent.KEYCODE_PAGE_UP, 75);
        SCANCODES.put(KeyEvent.KEYCODE_PAGE_DOWN, 78);
        SCANCODES.put(KeyEvent.KEYCODE_DPAD_RIGHT, 79);
        SCANCODES.put(KeyEvent.KEYCODE_DPAD_LEFT, 80);
        SCANCODES.put(KeyEvent.KEYCODE_DPAD_DOWN, 81);
        SCANCODES.put(KeyEvent.KEYCODE_DPAD_UP, 82);
        SCANCODES.put(KeyEvent.KEYCODE_CTRL_LEFT, 224);
        SCANCODES.put(KeyEvent.KEYCODE_SHIFT_LEFT, 225);
        SCANCODES.put(KeyEvent.KEYCODE_ALT_LEFT, 226);
        SCANCODES.put(KeyEvent.KEYCODE_CTRL_RIGHT, 228);
        SCANCODES.put(KeyEvent.KEYCODE_SHIFT_RIGHT, 229);
        SCANCODES.put(KeyEvent.KEYCODE_ALT_RIGHT, 230);
    }

    private final ServerSocket server;
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed;
    private volatile byte[] lastPause;
    private final int[] axes = new int[6];
    private int hatX;
    private boolean triggerKeys; // la manette envoie des touches L2/R2
    private final boolean[] triggers = new boolean[2];
    private static final int SCANCODE_LCTRL = 224;
    private static final int SCANCODE_LALT = 226;
    private int hatY;

    InputBridge() throws IOException {
        // IPv4 explicite : sur Android, getLoopbackAddress() renvoie ::1 (IPv6), or le moteur
        // se connecte a 127.0.0.1.
        server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        Thread accept = new Thread(this::acceptLoop, "daikatana-input");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Thread d'envoi (pas de reseau sur le thread UI) : un client a la fois, reconnexion possible. */
    private void acceptLoop() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                socket.setTcpNoDelay(true);
                OutputStream out = socket.getOutputStream();
                byte[] pending = lastPause;
                queue.clear();
                if (pending != null) queue.offer(pending); // etat de pause courant
                while (!closed) {
                    out.write(queue.take());
                    out.flush();
                }
            } catch (IOException e) {
                // jeu deconnecte : on attend une nouvelle connexion
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // deja fermee
        }
    }

    /** Fige (true) ou relance (false) la boucle du jeu. */
    void setPaused(boolean paused) {
        send(MSG_PAUSE, 0, paused ? 1 : 0);
    }

    /** @return true si la touche a ete transmise au jeu. */
    boolean onKey(KeyEvent event) {
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        boolean down = action == KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() > 0) return true;
        int keyCode = event.getKeyCode();
        boolean fromGamepad = isGamepad(event.getSource());

        // Gachettes : envoyees comme touches clavier (R2 = Ctrl, L2 = Alt, liees a +attack et
        // +zoom par autoexec.cfg). Le moteur gere mal les gachettes de manette (tir qui ne
        // s'arrete plus). Si la manette envoie des touches L2/R2 (comme l'Odin), elles font
        // foi et l'axe analogique est ignore.
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            triggerKeys = true;
            setTrigger(keyCode == KeyEvent.KEYCODE_BUTTON_R2 ? 1 : 0, down);
            return true;
        }
        int button = BUTTONS.get(keyCode, -1);
        if (button >= 0 && (fromGamepad || !isDpad(keyCode))) {
            send(MSG_BUTTON, button, down ? 1 : 0);
            return true;
        }
        int scancode = SCANCODES.get(keyCode, -1);
        if (scancode >= 0) {
            send(MSG_KEY, scancode, down ? 1 : 0);
            return true;
        }
        return false;
    }

    /** Sticks, gachettes analogiques et croix directionnelle "hat". */
    boolean onMotion(MotionEvent event) {
        if (!isGamepad(event.getSource()) || event.getAction() != MotionEvent.ACTION_MOVE) return false;
        axis(event, AXIS_LX, MotionEvent.AXIS_X, false);
        axis(event, AXIS_LY, MotionEvent.AXIS_Y, false);
        axis(event, AXIS_RX, MotionEvent.AXIS_Z, false);
        axis(event, AXIS_RY, MotionEvent.AXIS_RZ, false);
        if (!triggerKeys) {
            float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
            analogTrigger(0, lt);
            analogTrigger(1, rt);
        }

        int newHatX = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_X));
        int newHatY = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (newHatX != hatX) {
            if (hatX < 0) send(MSG_BUTTON, BTN_DPAD_LEFT, 0);
            if (hatX > 0) send(MSG_BUTTON, BTN_DPAD_RIGHT, 0);
            if (newHatX < 0) send(MSG_BUTTON, BTN_DPAD_LEFT, 1);
            if (newHatX > 0) send(MSG_BUTTON, BTN_DPAD_RIGHT, 1);
            hatX = newHatX;
        }
        if (newHatY != hatY) {
            if (hatY < 0) send(MSG_BUTTON, BTN_DPAD_UP, 0);
            if (hatY > 0) send(MSG_BUTTON, BTN_DPAD_DOWN, 0);
            if (newHatY < 0) send(MSG_BUTTON, BTN_DPAD_UP, 1);
            if (newHatY > 0) send(MSG_BUTTON, BTN_DPAD_DOWN, 1);
            hatY = newHatY;
        }
        return true;
    }

    private void axis(MotionEvent event, int sdlAxis, int androidAxis, boolean invert) {
        float v = event.getAxisValue(androidAxis);
        InputDevice.MotionRange range = event.getDevice() == null ? null
                : event.getDevice().getMotionRange(androidAxis, event.getSource());
        if (range != null && Math.abs(v) < range.getFlat()) v = 0f;
        if (invert) v = -v;
        setAxis(sdlAxis, Math.max(-32768, Math.min(32767, Math.round(v * 32767))));
    }

    /** Gachette analogique -> appui franc, avec hysteresis (enfoncee > 50 %, relachee < 25 %). */
    private void analogTrigger(int trigger, float value) {
        if (!triggers[trigger] && value > 0.5f) setTrigger(trigger, true);
        else if (triggers[trigger] && value < 0.25f) setTrigger(trigger, false);
    }

    /** trigger 0 = L2 (Alt gauche), 1 = R2 (Ctrl gauche). */
    private void setTrigger(int trigger, boolean pressed) {
        if (triggers[trigger] == pressed) return;
        triggers[trigger] = pressed;
        send(MSG_KEY, trigger == 1 ? SCANCODE_LCTRL : SCANCODE_LALT, pressed ? 1 : 0);
    }

    private void setAxis(int sdlAxis, int value) {
        if (axes[sdlAxis] == value) return;
        axes[sdlAxis] = value;
        send(MSG_AXIS, sdlAxis, value);
    }

    private static boolean isGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static boolean isDpad(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** Journal des entrees envoyees au jeu (version de debug uniquement). */
    static boolean debug;

    private void send(int type, int code, int value) {
        if (debug) android.util.Log.d("OpenKatanaInput", "type=" + type + " code=" + code + " value=" + value);
        byte[] msg = {
                (byte) type, 0, (byte) code, (byte) (code >> 8),
                (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24),
        };
        if (type == MSG_PAUSE) lastPause = msg;
        queue.offer(msg);
    }
}
