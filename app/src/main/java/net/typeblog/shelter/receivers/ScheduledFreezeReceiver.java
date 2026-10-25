package net.typeblog.shelter.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.typeblog.shelter.ui.DummyActivity;
import net.typeblog.shelter.util.SettingsManager;

// Receives the daily "freeze" / "unfreeze" alarms set by the Scheduled Freeze
// feature, and forwards the request to DummyActivity which runs in the work
// profile. Also re-schedules the alarms after a reboot.
public class ScheduledFreezeReceiver extends BroadcastReceiver {
    public static final String ACTION_SCHEDULED_FREEZE = "net.typeblog.shelter.action.SCHEDULED_FREEZE";
    public static final String ACTION_SCHEDULED_UNFREEZE = "net.typeblog.shelter.action.SCHEDULED_UNFREEZE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Alarms are cleared after a reboot, re-schedule them
            SettingsManager.getInstance().scheduleScheduledFreeze();
            return;
        }

        if (ACTION_SCHEDULED_FREEZE.equals(action) || ACTION_SCHEDULED_UNFREEZE.equals(action)) {
            Intent activity = new Intent(context, DummyActivity.class);
            activity.setAction(ACTION_SCHEDULED_FREEZE.equals(action)
                    ? DummyActivity.PUBLIC_FREEZE_ALL_APPS
                    : DummyActivity.PUBLIC_UNFREEZE_ALL_APPS);
            activity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(activity);
        }
    }
}
