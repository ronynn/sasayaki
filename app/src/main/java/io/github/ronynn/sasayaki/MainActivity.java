package io.github.ronynn.sasayaki;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity
{
  private static final int REQ_VPN = 101;
  private static final int REQ_NOTIF = 102;

  private EditText etDown;
  private EditText etUp;
  private Button btnStartStop;
  private boolean active;

  // Activity lifecycle
  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    etDown = findViewById(R.id.etDownKbps);
    etUp = findViewById(R.id.etUpKbps);
    btnStartStop = findViewById(R.id.btnStartStop);

    askPermissions();

    findViewById(R.id.btn2g).setOnClickListener(v -> setRates(150, 50));
    findViewById(R.id.btnSlow3g).setOnClickListener(v -> setRates(400, 150));
    findViewById(R.id.btn3g).setOnClickListener(v -> setRates(900, 300));

    btnStartStop.setOnClickListener(v -> {
      if (!active)
      {
        requestVpnAndStart();
      }
      else
      {
        stopServiceNow();
      }
    });
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data)
  {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_VPN)
    {
      if (resultCode == RESULT_OK)
      {
        launchService();
      }
      else
      {
        Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show();
      }
    }
  }

  // Controls and permissions
  private void askPermissions()
  {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    {
      if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      {
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
      }
    }
  }

  private void setRates(int downKbps, int upKbps)
  {
    etDown.setText(String.valueOf(downKbps));
    etUp.setText(String.valueOf(upKbps));
  }

  private void requestVpnAndStart()
  {
    Intent intent = VpnService.prepare(this);
    if (intent != null)
    {
      startActivityForResult(intent, REQ_VPN);
    }
    else
    {
      launchService();
    }
  }

  private void launchService()
  {
    int down = parseOrDefault(etDown.getText().toString(), 400);
    int up = parseOrDefault(etUp.getText().toString(), 150);

    Intent svc = new Intent(this, ThrottleVpnService.class);
    svc.setAction(ThrottleVpnService.ACTION_START);
    svc.putExtra(ThrottleVpnService.EXTRA_DOWN, down);
    svc.putExtra(ThrottleVpnService.EXTRA_UP, up);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    {
      startForegroundService(svc);
    }
    else
    {
      startService(svc);
    }
    active = true;
    btnStartStop.setText("Stop");
  }

  private void stopServiceNow()
  {
    Intent svc = new Intent(this, ThrottleVpnService.class);
    svc.setAction(ThrottleVpnService.ACTION_STOP);
    startService(svc);
    active = false;
    btnStartStop.setText("Start");
  }

  private int parseOrDefault(String raw, int def)
  {
    try
    {
      int parsed = Integer.parseInt(raw.trim());
      return parsed > 0 ? parsed : def;
    }
    catch (NumberFormatException e)
    {
      return def;
    }
  }
}