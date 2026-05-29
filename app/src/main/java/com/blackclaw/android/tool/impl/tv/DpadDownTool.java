package com.blackclaw.android.tool.impl.tv;

import android.view.KeyEvent;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;

public class DpadDownTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_down";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_down);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Down button on the remote. Moves focus to the element below the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control down directional key. Moves focus to the element below the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_DOWN;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Down";
    }
}
