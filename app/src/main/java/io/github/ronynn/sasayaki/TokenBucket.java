package io.github.ronynn.sasayaki;

public class TokenBucket
{
  private final long capacityBytes;
  private final double refillBytesPerMs;
  private double tokens;
  private long lastTimeMs;

  // Rate limiting
  public TokenBucket(int kbps)
  {
    int safeKbps = Math.max(kbps, 32);
    long bytesPerSec = (safeKbps * 1000L) / 8L;
    this.capacityBytes = Math.max(bytesPerSec / 4L, 1500L);
    this.refillBytesPerMs = bytesPerSec / 1000.0;
    this.tokens = this.capacityBytes;
    this.lastTimeMs = System.currentTimeMillis();
  }

  public synchronized void consume(int byteCount) throws InterruptedException
  {
    if (byteCount <= 0)
    {
      return;
    }
    while (true)
    {
      refill();
      if (tokens >= byteCount)
      {
        tokens -= byteCount;
        return;
      }
      double needed = byteCount - tokens;
      long sleepMs = Math.max(1L, (long) Math.ceil(needed / refillBytesPerMs));
      Thread.sleep(sleepMs);
    }
  }

  private void refill()
  {
    long now = System.currentTimeMillis();
    long elapsed = now - lastTimeMs;
    if (elapsed > 0)
    {
      tokens = Math.min(capacityBytes, tokens + elapsed * refillBytesPerMs);
      lastTimeMs = now;
    }
  }
}