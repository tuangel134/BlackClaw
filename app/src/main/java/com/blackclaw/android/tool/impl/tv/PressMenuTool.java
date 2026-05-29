package com.blackclaw.android.tool.impl.tv;

import android.view.KeyEvent;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;

public class PressMenuTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "press_menu";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_press_menu);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Menu button on the remote. Opens context menu or settings in the current app.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control menu key. Opens the context menu or settings in the current app.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_MENU;
    }

    @Override
    protected String getKeyLabel() {
        return "Menu";
    }
}
