package com.blackclaw.android.tool.impl.tv;

import android.view.KeyEvent;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.R;

public class PressPowerTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "press_power";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_press_power);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Power button. May turn off the screen or put the device to sleep.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the power key. May turn off the screen or put the device to sleep.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_POWER;
    }

    @Override
    protected String getKeyLabel() {
        return "Power";
    }
}
