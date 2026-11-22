package com.strek.assistbutton;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Receives the {@link Helper}'s Binder. Protected by
 * INTERACT_ACROSS_USERS_FULL in the manifest (only shell/system hold it), and
 * the caller UID is checked again here.
 */
public class HelperProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if ((caller == 2000 || caller == 0) && Helper.METHOD_BINDER.equals(method) && extras != null) {
            IBinder binder = extras.getBinder(Helper.EXTRA_BINDER);
            if (binder != null) {
                HelperClient.setBinder(binder);
            }
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
