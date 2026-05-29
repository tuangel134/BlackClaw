package com.blackclaw.android.tool.impl.tv;

import android.view.KeyEvent;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;

public class DpadRightTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_right";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_right);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Right button on the remote. Moves focus to the element on the right of the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control right directional key. Moves focus to the element to the right of the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Right";
    }
}
