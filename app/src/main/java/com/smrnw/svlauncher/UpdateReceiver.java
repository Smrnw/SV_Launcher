package com.smrnw.svlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Receives the installer's answer for an update. The usual answer is "the user has
 * to confirm": then the system confirmation screen is shown. On success the app is
 * replaced and restarted by the system, so there is nothing to do.
 */
public final class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (RuntimeException e) {
                Toast.makeText(context, R.string.update_error_install, Toast.LENGTH_LONG).show();
            }
            return;
        }
        // Success is delivered to the new version, which may start before the home
        // service reconnects; the mark is left for the service then.
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        // Cancelled or failed: SV Launcher stays as it is, nothing to bring back.
        HomeService.expectSelfUpdate(context, false);
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, R.string.update_error_install, Toast.LENGTH_LONG).show();
        }
    }
}
