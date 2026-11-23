package io.github.ronynn.sasayaki;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ThrottleVpnService extends VpnService
{
  public static final String ACTION_START = "io.github.ronynn.sasayaki.START";
  public static final String ACTION_STOP = "io.github.ronynn.sasayaki.STOP";
  public static final String EXTRA_DOWN = "down_kbps";
  public static final String EXTRA_UP = "up_kbps";

  private static final String CHANNEL_ID = "sasayaki_vpn";
  private ParcelFileDescriptor tunFd;
  private Thread loopThread;
  private volatile boolean running;

  // Service lifecycle
  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    if (intent != null && ACTION_STOP.equals(intent.getAction()))
    {
      stopVpn();
      return START_NOT_STICKY;
    }

    int downKbps = intent != null ? intent.getIntExtra(EXTRA_DOWN, 400) : 400;
    int upKbps = intent != null ? intent.getIntExtra(EXTRA_UP, 150) : 150;

    Notification notif = buildNotification(downKbps, upKbps);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    {
      startForeground(1, notif);
    }
    else
    {
      startForeground(1, notif);
    }

    startVpn(downKbps, upKbps);
    return START_STICKY;
  }

  private void startVpn(int downKbps, int upKbps)
  {
    if (running)
    {
      return;
    }

    Builder builder = new Builder();
    builder.setSession("io.github.ronynn.sasayaki")
      .addAddress("10.0.0.2", 32)
      .addRoute("0.0.0.0", 0)
      .addDnsServer("8.8.8.8")
      .setMtu(1400);

    try
    {
      builder.addDisallowedApplication(getPackageName());
    }
    catch (Exception ignored)
    {
    }

    tunFd = builder.establish();
    if (tunFd == null)
    {
      stopSelf();
      return;
    }

    running = true;
    TokenBucket upBucket = new TokenBucket(upKbps);
    TokenBucket downBucket = new TokenBucket(downKbps);

    loopThread = new Thread(() -> runUdpRelay(upBucket, downBucket), "SasayakiRelay");
    loopThread.start();
  }

  // Packet relay and throttling
  private void runUdpRelay(TokenBucket upBucket, TokenBucket downBucket)
  {
    FileInputStream in = new FileInputStream(tunFd.getFileDescriptor());
    FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor());
    byte[] buffer = new byte[32767];

    try (DatagramSocket relaySocket = new DatagramSocket())
    {
      protect(relaySocket);
      relaySocket.setSoTimeout(250);

      while (running && !Thread.interrupted())
      {
        int len = in.read(buffer);
        if (len > 28)
        {
          int version = (buffer[0] >> 4) & 0x0F;
          int ihl = (buffer[0] & 0x0F) * 4;
          int protocol = buffer[9] & 0xFF;

          if (version == 4 && protocol == 17 && len >= ihl + 8)
          {
            upBucket.consume(len);

            byte[] srcIp = Arrays.copyOfRange(buffer, 12, 16);
            byte[] dstIp = Arrays.copyOfRange(buffer, 16, 20);
            int srcPort = ((buffer[ihl] & 0xFF) << 8) | (buffer[ihl + 1] & 0xFF);
            int dstPort = ((buffer[ihl + 2] & 0xFF) << 8) | (buffer[ihl + 3] & 0xFF);
            int udpLen = ((buffer[ihl + 4] & 0xFF) << 8) | (buffer[ihl + 5] & 0xFF);
            int payloadOffset = ihl + 8;
            int payloadLen = Math.min(udpLen - 8, len - payloadOffset);

            if (payloadLen > 0)
            {
              InetAddress targetAddr = InetAddress.getByAddress(dstIp);
              DatagramPacket sendPkt = new DatagramPacket(buffer, payloadOffset, payloadLen, targetAddr, dstPort);
              relaySocket.send(sendPkt);

              byte[] respBuf = new byte[4096];
              DatagramPacket recvPkt = new DatagramPacket(respBuf, respBuf.length);
              try
              {
                relaySocket.receive(recvPkt);
                byte[] replyIpPacket = buildIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, respBuf, recvPkt.getLength());
                downBucket.consume(replyIpPacket.length);
                synchronized (out)
                {
                  out.write(replyIpPacket);
                }
              }
              catch (IOException ignored)
              {
              }
            }
          }
        }
      }
    }
    catch (IOException | InterruptedException e)
    {
      Thread.currentThread().interrupt();
    }
  }

  // IPv4 packet construction
  private byte[] buildIpv4UdpPacket(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort, byte[] payload, int payloadLen)
  {
    int totalLen = 28 + payloadLen;
    ByteBuffer pkt = ByteBuffer.allocate(totalLen);

    pkt.put((byte) 0x45);
    pkt.put((byte) 0x00);
    pkt.putShort((short) totalLen);
    pkt.putShort((short) 0);
    pkt.putShort((short) 0x4000);
    pkt.put((byte) 64);
    pkt.put((byte) 17);
    pkt.putShort((short) 0);
    pkt.put(srcIp);
    pkt.put(dstIp);

    int udpLen = 8 + payloadLen;
    pkt.putShort((short) srcPort);
    pkt.putShort((short) dstPort);
    pkt.putShort((short) udpLen);
    pkt.putShort((short) 0);
    pkt.put(payload, 0, payloadLen);

    byte[] raw = pkt.array();
    int checksum = computeIpv4Checksum(raw, 20);
    raw[10] = (byte) ((checksum >> 8) & 0xFF);
    raw[11] = (byte) (checksum & 0xFF);
    return raw;
  }

  private int computeIpv4Checksum(byte[] header, int len)
  {
    long sum = 0;
    for (int i = 0; i < len; i += 2)
    {
      if (i == 10)
      {
        continue;
      }
      int word = ((header[i] & 0xFF) << 8) | (header[i + 1] & 0xFF);
      sum += word;
    }
    while ((sum >> 16) != 0)
    {
      sum = (sum & 0xFFFF) + (sum >> 16);
    }
    return (int) (~sum & 0xFFFF);
  }

  // Cleanup and notification
  private void stopVpn()
  {
    running = false;
    if (loopThread != null)
    {
      loopThread.interrupt();
    }
    if (tunFd != null)
    {
      try
      {
        tunFd.close();
      }
      catch (IOException ignored)
      {
      }
      tunFd = null;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
    {
      stopForeground(Service.STOP_FOREGROUND_REMOVE);
    }
    else
    {
      stopForeground(true);
    }
    stopSelf();
  }

  private Notification buildNotification(int downKbps, int upKbps)
  {
    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    {
      NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Speed Saver", NotificationManager.IMPORTANCE_LOW);
      NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null)
      {
        nm.createNotificationChannel(ch);
      }
      builder = new Notification.Builder(this, CHANNEL_ID);
    }
    else
    {
      builder = new Notification.Builder(this);
    }

    builder.setContentTitle("Speed Saver Active")
      .setContentText("Down " + downKbps + " Kbps / Up " + upKbps + " Kbps")
      .setSmallIcon(android.R.drawable.ic_media_pause);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    {
      return builder.build();
    }
    return builder.build();
  }

  @Override
  public void onDestroy()
  {
    stopVpn();
    super.onDestroy();
  }
}