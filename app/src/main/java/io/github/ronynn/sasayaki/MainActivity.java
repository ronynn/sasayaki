package io.github.ronynn.sasayaki;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends Activity {

    private AudioManager audioManager;
    private TextView tvBattery;
    private TextView tvStatus;
    private Handler handler;
    private Runnable batteryUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        tvBattery = (TextView) findViewById(R.id.tv_battery);
        tvStatus  = (TextView) findViewById(R.id.tv_status);

        findViewById(R.id.btn_method1).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { method1_legacyComm(); }
        });
        findViewById(R.id.btn_method2).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { method2_aggressiveInCall(); }
        });
        findViewById(R.id.btn_method3).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { method3_communicationDevice(); }
        });
        findViewById(R.id.btn_method4).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { method4_reflectionForceUse(); }
        });
        findViewById(R.id.btn_method5).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { method5_reflectionPhoneState(); }
        });
        findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetAudio(); }
        });

        handler = new Handler(Looper.getMainLooper());
        batteryUpdater = new Runnable() {
            @Override public void run() {
                updateBatteryInfo();
                handler.postDelayed(this, 2000);
            }
        };
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(batteryUpdater);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(batteryUpdater);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // Safety net: if the app dies while routing is active, restore normal audio.
        try { resetAudioInternal(); } catch (Exception ignored) {}
    }

    // ================= METHOD 1: legacy communication mode =================
    @SuppressWarnings("deprecation")
    private void method1_legacyComm() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            showStatus("Method 1", "applied without exception");
        } catch (Exception e) {
            showStatus("Method 1", "EXCEPTION: " + e.getMessage());
        }
    }

    // ================= METHOD 2: aggressive MODE_IN_CALL ===================
    @SuppressWarnings("deprecation")
    private void method2_aggressiveInCall() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_CALL);
            audioManager.setSpeakerphoneOn(false);
            showStatus("Method 2", "applied without exception");
        } catch (Exception e) {
            showStatus("Method 2", "EXCEPTION: " + e.getClass().getSimpleName()
                + " - " + e.getMessage());
        }
    }

    // ================= METHOD 3: modern API 31+ ============================
    @SuppressWarnings("deprecation")
    private void method3_communicationDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            showStatus("Method 3", "requires Android 12+ (API 31)");
            return;
        }
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            AudioDeviceInfo earpiece =
                findAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
            if (earpiece == null) {
                showStatus("Method 3", "no earpiece device found");
                return;
            }
            boolean ok = audioManager.setCommunicationDevice(earpiece);
            showStatus("Method 3", ok ? "setCommunicationDevice returned TRUE"
                                      : "setCommunicationDevice returned FALSE");
        } catch (Exception e) {
            showStatus("Method 3", "EXCEPTION: " + e.getMessage());
        }
    }

    // ================= METHOD 4: AudioSystem.setForceUse ===================
    @SuppressWarnings("deprecation")
    private void method4_reflectionForceUse() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);

            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            // 1 = FOR_MEDIA, 0 = FORCE_NONE (forces default routing to policy)
            java.lang.reflect.Method m =
                audioSystem.getMethod("setForceUse", int.class, int.class);
            Object result = m.invoke(null, 1, 0);
            showStatus("Method 4", "setForceUse OK, returned: " + result);
        } catch (Exception e) {
            showStatus("Method 4", "EXCEPTION: " + e.getClass().getSimpleName()
                + " - " + e.getMessage());
        }
    }

    // ================= METHOD 5: AudioSystem.setPhoneState =================
    @SuppressWarnings("deprecation")
    private void method5_reflectionPhoneState() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);

            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            // 2 = MODE_IN_CALL, 3 = MODE_IN_COMMUNICATION
            java.lang.reflect.Method m =
                audioSystem.getMethod("setPhoneState", int.class);
            Object result = m.invoke(null, 2);
            showStatus("Method 5", "setPhoneState(IN_CALL) OK, returned: " + result);
        } catch (Exception e) {
            showStatus("Method 5", "EXCEPTION: " + e.getClass().getSimpleName()
                + " - " + e.getMessage());
        }
    }

    // ================= RESET ===============================================
    @SuppressWarnings("deprecation")
    private void resetAudio() {
        try {
            resetAudioInternal();
            showStatus("RESET", "audio restored to normal");
        } catch (Exception e) {
            showStatus("RESET", "EXCEPTION: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void resetAudioInternal() {
        // 1. Clear communication device
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager.clearCommunicationDevice(); } catch (Exception ignored) {}
        }

        // 2. Reset speakerphone + mode
        try { audioManager.setSpeakerphoneOn(true); } catch (Exception ignored) {}
        try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}

        // 3. Reset hidden APIs too (in case Method 4 or 5 was used)
        try {
            Class<?> audioSystem = Class.forName("android.media.AudioSystem");

            java.lang.reflect.Method setPhoneState =
                audioSystem.getMethod("setPhoneState", int.class);
            setPhoneState.invoke(null, 0); // MODE_NORMAL

            java.lang.reflect.Method setForceUse =
                audioSystem.getMethod("setForceUse", int.class, int.class);
            setForceUse.invoke(null, 1, 0); // FOR_MEDIA, FORCE_NONE
        } catch (Exception ignored) {}
    }

    // ================= Helpers =============================================
    private AudioDeviceInfo findAudioDevice(int type) {
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == type) return d;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void showStatus(String method, String result) {
        String modeName;
        try {
            switch (audioManager.getMode()) {
                case AudioManager.MODE_NORMAL: modeName = "NORMAL"; break;
                case AudioManager.MODE_RINGTONE: modeName = "RINGTONE"; break;
                case AudioManager.MODE_IN_CALL: modeName = "IN_CALL"; break;
                case AudioManager.MODE_IN_COMMUNICATION: modeName = "IN_COMMUNICATION"; break;
                default: modeName = "(" + audioManager.getMode() + ")"; break;
            }
        } catch (Exception e) { modeName = "?"; }

        boolean sp = false;
        try { sp = audioManager.isSpeakerphoneOn(); } catch (Exception ignored) {}

        tvStatus.setText(method + ": " + result
            + "\n\nAudio state now: mode=" + modeName + ", speaker=" + sp
            + "\nListen to your music app — did it move to earpiece?");
    }

    private void updateBatteryInfo() {
        if (tvBattery == null) return;
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        long currentNow = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        long chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);

        int voltage = i != null ? i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) : -1;
        int tempTenths = i != null ? i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
        int status = i != null ? i.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN) : BatteryManager.BATTERY_STATUS_UNKNOWN;

        String statusText;
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: statusText = "Charging"; break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING: statusText = "Discharging"; break;
            case BatteryManager.BATTERY_STATUS_FULL: statusText = "Full"; break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: statusText = "Not charging"; break;
            default: statusText = "Unknown"; break;
        }

        String currentStr;
        if (currentNow == Long.MIN_VALUE || currentNow == 0) {
            currentStr = "N/A";
        } else {
            currentStr = (currentNow / 1000L) + " mA"
                + (currentNow < 0 ? " (discharging)" : " (charging)");
        }

        String chargeStr = (chargeCounter == Long.MIN_VALUE || chargeCounter == 0)
            ? "N/A" : (chargeCounter / 1000L) + " mAh";

        tvBattery.setText("🔋 " + level + "%  •  " + statusText
            + "  •  " + (voltage > 0 ? voltage + " mV" : "— mV")
            + "  •  " + (tempTenths > 0 ? (tempTenths / 10.0) + "°C" : "—°C")
            + "\nCurrent: " + currentStr + "  •  Counter: " + chargeStr);
    }
}