package com.gfn.touchcontrols;

interface IHidService {
    String openHid() = 1;
    void closeHid() = 2;
    void sendKeyboard(int modifiers, in byte[] keys) = 3;
    void sendMouse(int buttons, int dx, int dy, int wheel) = 4;
    void destroy() = 16777114;
}
