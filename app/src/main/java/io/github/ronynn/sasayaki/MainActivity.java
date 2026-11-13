package io.github.ronynn.sasayaki;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private AudioManager audioManager;
    private TextView tvAudioStatus;
    private TextView tvBattery;
    private TextView tvSysInfo;
    private ListView lvApps;

    private final List<AppEntry> appEntries = new ArrayList<AppEntry>();

    // =================================================================
    // Lifecycle
    // =================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        setupTabs();
        setupAudioTab();
        setupAppsTab();
        setupBatteryTab();
        setupSysInfoTab();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh battery + list every time the app comes back to foreground.
        updateBatteryInfo();
        if (hasUsageStatsPermission()) {
            refreshAppList();
        }
        updateAudioStatus();
    }

    private void setupTabs() {
        TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        tabHost.setup();

        addTab(tabHost, "Audio", R.id.tab_audio);
        addTab(tabHost, "Apps", R.id.tab_apps);
        addTab(tabHost, "Battery", R.id.tab_battery);
        addTab(tabHost, "System", R.id.tab_sysinfo);
    }

    private void addTab(TabHost host, String title, int viewId) {
        TabHost.TabSpec spec = host.newTabSpec(title);
        spec.setIndicator(title);
        spec.setContent(viewId);
        host.addTab(spec);
    }

    // =================================================================
    // 1. Audio routing
    // =================================================================

    private void setupAudioTab() {
        tvAudioStatus = (TextView) findViewById(R.id.tv_audio_status);

        Button on = (Button) findViewById(R.id.btn_earpiece_on);
        on.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { routeToEarpiece(); }
        });

        Button off = (Button) findViewById(R.id.btn_earpiece_off);
        off.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restoreAudio(); }
        });

        updateAudioStatus();
    }

    @SuppressWarnings("deprecation")
    private void routeToEarpiece() {
        try {
            // Set the audio mode first — required before routing.
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+ : modern API. Routes communication audio to earpiece.
                AudioDeviceInfo earpiece = null;
                for (AudioDeviceInfo dev : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        earpiece = dev;
                        break;
                    }
                }
                if (earpiece != null) {
                    audioManager.setCommunicationDevice(earpiece);
                }
                // Legacy fallback as a nudge — some OEMs honour it for media.
                audioManager.setSpeakerphoneOn(false);
            } else {
                // Legacy path for pre-Android 12.
                audioManager.setSpeakerphoneOn(false);
            }

            tvAudioStatus.setText("Status: earpiece (best-effort)");
            Toast.makeText(this, "Audio routed to earpiece", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            tvAudioStatus.setText("Status: error — " + e.getMessage());
            Toast.makeText(this, "Routing failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void restoreAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }
            audioManager.setSpeakerphoneOn(true);
            audioManager.setMode(AudioManager.MODE_NORMAL);
            tvAudioStatus.setText("Status: normal");
            Toast.makeText(this, "Normal audio restored", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            tvAudioStatus.setText("Status: restore failed — " + e.getMessage());
            Toast.makeText(this, "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void updateAudioStatus() {
        if (tvAudioStatus == null) return;
        int mode = audioManager.getMode();
        boolean speaker = audioManager.isSpeakerphoneOn();
        String label;
        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            label = speaker ? "Status: in-communication, speaker ON"
                            : "Status: in-communication, earpiece";
        } else if (mode == AudioManager.MODE_NORMAL) {
            label = "Status: normal";
        } else {
            label = "Status: mode=" + mode;
        }
        tvAudioStatus.setText(label);
    }

    // =================================================================
    // 2. Running / recent apps (UsageStatsManager)
    // =================================================================

    private void setupAppsTab() {
        lvApps = (ListView) findViewById(R.id.lv_apps);

        Button grant = (Button) findViewById(R.id.btn_grant_usage);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hasUsageStatsPermission()) {
                    refreshAppList();
                    Toast.makeText(MainActivity.this,
                            "Usage access already granted — refreshing", Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                                "Could not open Usage Access settings", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        lvApps.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                AppEntry entry = appEntries.get(position);
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + entry.packageName));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Cannot open App Info for " + entry.packageName,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        refreshAppList();
    }

    private void refreshAppList() {
        if (lvApps == null) return;

        if (!hasUsageStatsPermission()) {
            String[] placeholder = { "Grant Usage Access to see running apps" };
            lvApps.setAdapter(new ArrayAdapter<String>(
                    this, android.R.layout.simple_list_item_1, placeholder));
            return;
        }

        UsageStatsManager usm =
                (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        // 30-minute window: apps active in the last half hour.
        // Force-stopped apps drop out of UsageStats history, so they won't appear.
        List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, now - 30L * 60L * 1000L, now);

        appEntries.clear();
        if (stats != null) {
            for (UsageStats s : stats) {
                if (s.getLastTimeUsed() > 0) {
                    appEntries.add(new AppEntry(s.getPackageName(), s.getLastTimeUsed()));
                }
            }
        }

        Collections.sort(appEntries, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return Long.compare(b.lastUsed, a.lastUsed); // most recent first
            }
        });

        List<String> labels = new ArrayList<String>();
        PackageManager pm = getPackageManager();
        for (AppEntry entry : appEntries) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(entry.packageName, 0);
                String name = pm.getApplicationLabel(ai).toString();
                labels.add(name + "\n" + entry.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                labels.add(entry.packageName);
            }
        }

        if (labels.isEmpty()) {
            labels.add("(no recent apps in the last 30 minutes)");
            appEntries.clear(); // prevent index errors on tap
        }

        lvApps.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, labels));
    }

    private boolean hasUsageStatsPermission() {
        UsageStatsManager usm =
                (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 24L * 60L * 60L * 1000L, now);
        return stats != null && !stats.isEmpty();
    }

    private static class AppEntry {
        final String packageName;
        final long lastUsed;
        AppEntry(String pkg, long time) {
            packageName = pkg;
            lastUsed = time;
        }
    }

    // =================================================================
    // 3. Battery information
    // =================================================================

    private void setupBatteryTab() {
        tvBattery = (TextView) findViewById(R.id.tv_battery);
        updateBatteryInfo();
    }

    private void updateBatteryInfo() {
        if (tvBattery == null) return;

        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);

        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        long currentNow = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        long chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);

        // Status, health, voltage, temperature all come from the sticky broadcast —
        // all available since API 1, so no version guard needed.
        Intent batteryIntent = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        int voltage    = batteryIntent != null
                ? batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) : -1;
        int tempTenths = batteryIntent != null
                ? batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
        int health     = batteryIntent != null
                ? batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) : -1;
        int status     = batteryIntent != null
                ? batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN)
                : BatteryManager.BATTERY_STATUS_UNKNOWN;

        String statusText;
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:      statusText = "Charging";     break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:   statusText = "Discharging";  break;
            case BatteryManager.BATTERY_STATUS_FULL:          statusText = "Full";         break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:  statusText = "Not charging"; break;
            default:                                          statusText = "Unknown";      break;
        }

        String healthText;
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD:      healthText = "Good";      break;
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:  healthText = "Overheat";  break;
            case BatteryManager.BATTERY_HEALTH_DEAD:      healthText = "Dead";      break;
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: healthText = "Over voltage"; break;
            case BatteryManager.BATTERY_HEALTH_COLD:      healthText = "Cold";      break;
            default:                                      healthText = "Unknown";   break;
        }

        // Current may be Long.MIN_VALUE or 0 on some devices — handle gracefully.
        String currentStr;
        if (currentNow == Long.MIN_VALUE || currentNow == 0) {
            currentStr = "N/A";
        } else {
            currentStr = (currentNow / 1000L) + " mA"
                    + (currentNow < 0 ? " (discharging)" : " (charging)");
        }

        String chargeStr = (chargeCounter == Long.MIN_VALUE || chargeCounter == 0)
                ? "N/A" : (chargeCounter / 1000L) + " mAh";

        String info = "Level: " + level + "%\n"
                + "Status: " + statusText + "\n"
                + "Health: " + healthText + "\n"
                + "Voltage: " + (voltage > 0 ? voltage + " mV" : "N/A") + "\n"
                + "Temperature: " + (tempTenths > 0 ? (tempTenths / 10.0) + " °C" : "N/A") + "\n"
                + "Current now: " + currentStr + "\n"
                + "Charge counter: " + chargeStr;

        tvBattery.setText(info);
    }

    // =================================================================
    // 4. System information
    // =================================================================

    private void setupSysInfoTab() {
        tvSysInfo = (TextView) findViewById(R.id.tv_sysinfo);
        updateSystemInfo();
    }

    private void updateSystemInfo() {
        if (tvSysInfo == null) return;

        StringBuilder sb = new StringBuilder();

        // Device / build
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("CPU cores: ").append(Runtime.getRuntime().availableProcessors()).append("\n");
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
          .append("\n\n");

        // RAM
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        sb.append("Total RAM: ").append(memInfo.totalMem / (1024 * 1024)).append(" MB\n");
        sb.append("Available RAM: ").append(memInfo.availMem / (1024 * 1024)).append(" MB\n");
        sb.append("Low memory: ").append(memInfo.lowMemory).append("\n\n");

        // Storage
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long totalBytes = stat.getBlockCountLong() * stat.getBlockSizeLong();
            long freeBytes  = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            sb.append("Internal storage total: ").append(totalBytes / (1024 * 1024)).append(" MB\n");
            sb.append("Internal storage free:  ").append(freeBytes  / (1024 * 1024)).append(" MB\n\n");
        } catch (Exception ignored) { }

        // Extra details from /proc/meminfo (usually readable for non-root).
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("MemTotal")
                            || line.startsWith("MemFree")
                            || line.startsWith("Buffers")
                            || line.startsWith("Cached")
                            || line.startsWith("SwapTotal")
                            || line.startsWith("SwapFree")) {
                        sb.append(line).append("\n");
                    }
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) { }

        tvSysInfo.setText(sb.toString());
    }
}