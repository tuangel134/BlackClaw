package com.blackclaw.android.tool.impl;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;

/**
 * Transparent Activity used to gain foreground focus for reading clipboard content.
 * Android 10+ restricts background apps from reading the clipboard; the app must be in the foreground.
 * Clipboard reading is performed in onWindowFocusChanged(true) to ensure the window has gained focus.
 */
public class ClipboardReaderActivity extends Activity {

    static volatile String clipboardResult;
    static volatile String clipboardError;
    static CountDownLatch latch;

    private boolean hasRead = false;

    static void prepare() {
        clipboardResult = null;
        clipboardError = null;
        latch = new CountDownLatch(1);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !hasRead) {
            hasRead = true;
            readClipboard();
            if (latch != null) {
                latch.countDown();
            }
            finish();
        }
    }

    private void readClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                clipboardError = "Unable to access clipboard service";
            } else if (!clipboard.hasPrimaryClip()) {
                clipboardResult = "Clipboard is empty";
            } else {
                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData == null || clipData.getItemCount() == 0) {
                    clipboardResult = "Clipboard is empty";
                } else {
                    CharSequence text = clipData.getItemAt(0).getText();
                    if (text == null || text.length() == 0) {
                        clipboardResult = "Clipboard has no text content";
                    } else {
                        clipboardResult = text.toString();
                    }
                }
            }
        } catch (SecurityException e) {
            clipboardError = "No permission to access clipboard";
        } catch (Exception e) {
            clipboardError = "Failed to read clipboard: " + e.getMessage();
        }
    }
}
