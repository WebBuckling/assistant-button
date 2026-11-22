package com.strek.assistbutton;

/** Shell commands run over ADB. */
final class Commands {

    private Commands() {
    }

    /**
     * Opens Circle to Search with a real screenshot, like Pixel / MiCTS do.
     *
     * <p>Calls {@code IVoiceInteractionManagerService.showSessionFromSession}
     * (transaction 3) with no token, a Bundle {@code {"omni.entry_point": 1}},
     * flags 7 (SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_ASSIST_GESTURE)
     * and an attribution tag. Bundle layout: non-null marker, length 48, magic
     * "BNDL", 1 entry, key (String16), VAL_INTEGER, value.
     *
     * <p>Requires the Google app as the default digital assistant. Shell
     * privileges are needed: a regular app can't pass the screenshot flags.
     */
    static final String CIRCLE_TO_SEARCH =
            "service call voiceinteraction 3 null"
                    + " i32 1 i32 48 i32 1279544898 i32 1 s16 omni.entry_point i32 1 i32 1"
                    + " i32 7 s16 assistbutton";

    /**
     * After Circle to Search / the Gemini overlay is closed, the Google app often
     * leaves its voice-interaction session marked as shown ({@code mShown=true})
     * with nothing on screen, and then ignores the next call. If that's the case
     * and no Google window is in front, restart the Google app first.
     */
    private static final String RESET_STALE_SESSION =
            "if dumpsys voiceinteraction | grep -q 'mShown=true'"
                    + " && ! dumpsys window | grep mCurrentFocus | grep -q googlequicksearchbox;"
                    + " then am force-stop com.google.android.googlequicksearchbox; sleep 0.5; fi; ";

    /** Circle to Search, healing a stale assistant session first. */
    static final String OPEN_CIRCLE_TO_SEARCH = RESET_STALE_SESSION + CIRCLE_TO_SEARCH;

    /** {@code service call} prints this when the call returned {@code true}. */
    static final String SUCCESS = "00000000 00000001";

    static String grantSecureSettings(String packageName) {
        return "pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS";
    }
}
