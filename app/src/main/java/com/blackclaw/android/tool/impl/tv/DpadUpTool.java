package com.blackclaw.android.tool.impl.tv;

import android.view.KeyEvent;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;

public class DpadUpTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_up";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_up);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Up button on the remote. Moves focus to the element above the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control up directional key. Moves focus to the element above the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_UP;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Up";
    }
}
