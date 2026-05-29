package com.blackclaw.android.tool.impl;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;
import com.blackclaw.android.utils.XLog;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Make a phone call to a contact by name or number.
 * Resolves contact names via ContactsContract, then opens the dialer with ACTION_DIAL.
 * Uses ACTION_DIAL (not ACTION_CALL) — shows the dialer screen so user confirms the call.
 */
public class MakeCallTool extends BaseTool {

    private static final String TAG = "MakeCallTool";

    @Override
    public String getName() { return "make_call"; }

    @Override
    public String getDisplayName() { return "Make Call"; }

    @Override
    public String getDescriptionEN() {
        return "Make a phone call to a contact name or phone number. "
                + "Resolves contact names from the phone's contacts list. "
                + "Opens the dialer so the user can confirm the call.";
    }

    @Override
    public String getDescriptionCN() {
        return "Make a phone call to a contact name or phone number. "
                + "Resolves contact names from the phone's contacts list.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("contact", "string", "Contact name (e.g. 'Mom', 'John') or phone number", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String contact = requireString(params, "contact").trim();
        Context ctx = ClawApplication.Companion.getInstance();

        XLog.i(TAG, "Making call to: " + contact);

        // Check if it's already a phone number
        if (contact.matches("[\\d\\s\\-+()]{7,}")) {
            String number = contact.replaceAll("[\\s\\-()]", "");
            return dialNumber(ctx, number, contact);
        }

        // Look up contact name in phone contacts
        String number = lookupContact(ctx, contact);
        if (number != null) {
            XLog.i(TAG, "Resolved '" + contact + "' to " + number);
            return dialNumber(ctx, number, contact);
        }

        XLog.w(TAG, "Contact '" + contact + "' not found in phone contacts");
        return ToolResult.error("Contact '" + contact + "' not found in phone contacts. "
                + "Please provide the phone number directly, or check the contact name.");
    }

    private ToolResult dialNumber(Context ctx, String number, String displayName) {
        try {
            Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(dialIntent);
            XLog.i(TAG, "Dialer opened for " + displayName + " (" + number + ")");
            return ToolResult.success("Dialer opened for " + displayName + " (" + number + "). User can tap Call to connect.");
        } catch (Exception e) {
            XLog.e(TAG, "Failed to open dialer", e);
            return ToolResult.error("Failed to open dialer: " + e.getMessage());
        }
    }

    private String lookupContact(Context ctx, String name) {
        try {
            ContentResolver resolver = ctx.getContentResolver();
            Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            String[] projection = {ContactsContract.CommonDataKinds.Phone.NUMBER};
            String selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
            String[] args = {"%" + name + "%"};

            Cursor cursor = resolver.query(uri, projection, selection, args, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        return cursor.getString(0);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (SecurityException e) {
            XLog.w(TAG, "No contacts permission, cannot look up '" + name + "'", e);
        } catch (Exception e) {
            XLog.e(TAG, "Failed to look up contact '" + name + "'", e);
        }
        return null;
    }
}
