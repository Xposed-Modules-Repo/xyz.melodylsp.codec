package xyz.melodylsp.codec.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class DiagnosticEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DiagnosticEvents.ACTION.equals(intent.getAction())) return;
        DiagnosticEvents.record(context, intent);
    }
}
