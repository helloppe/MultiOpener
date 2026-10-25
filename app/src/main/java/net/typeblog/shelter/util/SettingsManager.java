package net.typeblog.shelter.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import net.typeblog.shelter.receivers.ScheduledFreezeReceiver;
import net.typeblog.shelter.services.PaymentStubService;
import net.typeblog.shelter.ui.DummyActivity;

public class SettingsManager {
    private static SettingsManager sInstance = null;

    public static void initialize(Context context) {
        sInstance = new SettingsManager(context);
    }

    public static SettingsManager getInstance() {
        return sInstance;
    }

    private LocalStorageManager mStorage = LocalStorageManager.getInstance();
    private Context mContext;

    private SettingsManager(Context context) {
        mContext = context;
    }

    private void syncSettingsToProfileBool(String name, boolean value) {
        Intent intent = new Intent(DummyActivity.SYNCHRONIZE_PREFERENCE);
        intent.putExtra("name", name);
        intent.putExtra("boolean", value);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Utility.transferIntentToProfile(mContext, intent);
        mContext.startActivity(intent);
    }

    private void syncSettingsToProfileInt(String name, int value) {
        Intent intent = new Intent(DummyActivity.SYNCHRONIZE_PREFERENCE);
        intent.putExtra("name", name);
        intent.putExtra("int", value);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Utility.transferIntentToProfile(mContext, intent);
        mContext.startActivity(intent);
    }

    // Enforce all settings
    public void applyAll() {
        applyCrossProfileFileChooser();
        applyPaymentStub();
    }

    // Read and apply the enabled state of the cross profile file chooser
    public void applyCrossProfileFileChooser() {
        boolean enabled = mStorage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER);
        mContext.getPackageManager().setComponentEnabledSetting(
                new ComponentName(mContext, CrossProfileDocumentsProvider.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    // Set the enabled state of the cross profile file chooser
    public void setCrossProfileFileChooserEnabled(boolean enabled) {
        mStorage.setBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled);
        applyCrossProfileFileChooser();
        syncSettingsToProfileBool(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled);
    }

    // Get the enabled state of the cross profile file chooser
    public boolean getCrossProfileFileChooserEnabled() {
        return mStorage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER);
    }

    // Set the blocked state of cross-profile contacts searching
    public void setBlockContactsSearchingEnabled(boolean enabled) {
        mStorage.setBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled);
        syncSettingsToProfileBool(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled);
    }

    // Get the blocked state of cross-profile contacts searching
    public boolean getBlockContactsSearchingEnabled() {
        return mStorage.getBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING);
    }

    // Set the enabled state of the auto freeze service
    // This does NOT need to be synchronized nor applied across profile
    public void setAutoFreezeServiceEnabled(boolean enabled) {
        mStorage.setBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled);
    }

    // Get the enabled state of the auto freeze service
    public boolean getAutoFreezeServiceEnabled() {
        return mStorage.getBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE);
    }

    // Set the delay for auto freeze service (in seconds)
    public void setAutoFreezeDelay(int seconds) {
        mStorage.setInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds);
        syncSettingsToProfileInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds);
    }

    // Get the delay for auto freeze service
    public int getAutoFreezeDelay() {
        int ret = mStorage.getInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY);
        if (ret == Integer.MIN_VALUE) {
            // Default delay is 0 seconds
            ret = 0;
        }
        return ret;
    }

    // Set the enabled state of "skip foreground"
    public void setSkipForegroundEnabled(boolean enabled) {
        mStorage.setBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled);
        syncSettingsToProfileBool(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled);
    }

    // Get the enabled state of "skip foreground"
    public boolean getSkipForegroundEnabled() {
        return mStorage.getBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND);
    }

    public boolean getPaymentStubEnabled() {
        return mStorage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB);
    }

    public void setPaymentStubEnabled(boolean enabled) {
        mStorage.setBoolean(LocalStorageManager.PREF_PAYMENT_STUB, enabled);
        applyPaymentStub();
    }

    // Enable / disable the payment stub component based on settings in local storage
    public void applyPaymentStub() {
        boolean enabled = mStorage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB);
        mContext.getPackageManager().setComponentEnabledSetting(
                new ComponentName(mContext, PaymentStubService.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    // === Scheduled freeze (time-of-day auto freeze / unfreeze) ===

    public boolean getScheduledFreezeEnabled() {
        return mStorage.getBoolean(LocalStorageManager.PREF_SCHEDULED_FREEZE_ENABLED);
    }

    // Minute of the day (0..1439), or -1 if not set
    public int getScheduledFreezeTime() {
        return mStorage.getInt(LocalStorageManager.PREF_SCHEDULED_FREEZE_TIME);
    }

    public int getScheduledUnfreezeTime() {
        return mStorage.getInt(LocalStorageManager.PREF_SCHEDULED_UNFREEZE_TIME);
    }

    public void setScheduledFreezeEnabled(boolean enabled) {
        mStorage.setBoolean(LocalStorageManager.PREF_SCHEDULED_FREEZE_ENABLED, enabled);
        scheduleScheduledFreeze();
    }

    public void setScheduledFreezeTime(int minuteOfDay) {
        mStorage.setInt(LocalStorageManager.PREF_SCHEDULED_FREEZE_TIME, minuteOfDay);
        scheduleScheduledFreeze();
    }

    public void setScheduledUnfreezeTime(int minuteOfDay) {
        mStorage.setInt(LocalStorageManager.PREF_SCHEDULED_UNFREEZE_TIME, minuteOfDay);
        scheduleScheduledFreeze();
    }

    // (Re-)schedule the daily alarms. Safe to call any time.
    public void scheduleScheduledFreeze() {
        android.app.AlarmManager am = mContext.getSystemService(android.app.AlarmManager.class);
        cancelScheduledFreeze();

        if (!getScheduledFreezeEnabled()) {
            return;
        }
        int freezeMin = getScheduledFreezeTime();
        int unfreezeMin = getScheduledUnfreezeTime();
        if (freezeMin < 0 && unfreezeMin < 0) {
            return;
        }
        if (freezeMin >= 0) {
            scheduleOne(am, ScheduledFreezeReceiver.ACTION_SCHEDULED_FREEZE, freezeMin, 1);
        }
        if (unfreezeMin >= 0) {
            scheduleOne(am, ScheduledFreezeReceiver.ACTION_SCHEDULED_UNFREEZE, unfreezeMin, 2);
        }
    }

    // Cancel all scheduled freeze alarms
    public void cancelScheduledFreeze() {
        android.app.AlarmManager am = mContext.getSystemService(android.app.AlarmManager.class);
        cancelOne(am, ScheduledFreezeReceiver.ACTION_SCHEDULED_FREEZE, 1);
        cancelOne(am, ScheduledFreezeReceiver.ACTION_SCHEDULED_UNFREEZE, 2);
    }

    private void scheduleOne(android.app.AlarmManager am, String action, int minuteOfDay, int requestCode) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(java.util.Calendar.MINUTE, minuteOfDay % 60);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        Intent intent = new Intent(action).setPackage(mContext.getPackageName());
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(mContext, requestCode, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        am.setRepeating(android.app.AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(),
                android.app.AlarmManager.INTERVAL_DAY, pi);
    }

    private void cancelOne(android.app.AlarmManager am, String action, int requestCode) {
        Intent intent = new Intent(action).setPackage(mContext.getPackageName());
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(mContext, requestCode, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }
}
