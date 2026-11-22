package com.strek.assistbutton;

import android.os.IBinder;
import android.os.Parcel;

/** Calls the {@link Helper} through the Binder it hands to {@link HelperProvider}. */
final class HelperClient {

    private static final Object LOCK = new Object();
    private static IBinder binder;

    private HelperClient() {
    }

    static void setBinder(IBinder b) {
        synchronized (LOCK) {
            binder = b;
            LOCK.notifyAll();
        }
        L.i("helper binder received");
    }

    /**
     * Returns a live helper Binder, waiting up to {@code waitMs} for one to arrive
     * (the helper sends it right after our process starts), or null.
     */
    static IBinder await(long waitMs) {
        long end = System.currentTimeMillis() + waitMs;
        synchronized (LOCK) {
            while (binder == null || !binder.isBinderAlive()) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return null;
                }
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return binder;
        }
    }

    /** Version of the running helper, or -1. */
    static int ping(IBinder b) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            return b.transact(Helper.TX_PING, data, reply, 0) ? reply.readInt() : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** "ok", "fail ...", or null if the helper is gone. */
    static String openCircleToSearch(IBinder b) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            return b.transact(Helper.TX_CTS, data, reply, 0) ? reply.readString() : null;
        } catch (Exception e) {
            L.i("helper call failed: " + e);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static void exit(IBinder b) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            b.transact(Helper.TX_EXIT, data, reply, 0);
        } catch (Exception ignored) {
            // Already gone.
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
