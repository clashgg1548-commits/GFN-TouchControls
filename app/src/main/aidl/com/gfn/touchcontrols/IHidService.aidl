package com.gfn.touchcontrols;

interface IHidService {
    String openHid();
    void closeHid();
    void sendKeyboard(int modifiers, in byte[] keys);
    void sendMouse(int buttons, int dx, int dy, int wheel);
    void destroy();
}
