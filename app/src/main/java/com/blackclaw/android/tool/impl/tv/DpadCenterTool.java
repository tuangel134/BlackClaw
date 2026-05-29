package com.blackclaw.android.tool.impl.tv;

import android.view.KeyEvent;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;

public class DpadCenterTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_center";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_center);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the OK/Center/Select button on the remote. Confirms the selection or clicks the currently focused element.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control confirm/OK key. Confirms the selection or clicks the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_CENTER;
    }

    @Override
    protected String getKeyLabel() {
        return "OK/Center";
    }
}
