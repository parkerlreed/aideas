package com.lvonasek.tofviewer;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public class DepthStreamServer {

  private static final String TAG = "DepthStreamServer";
  public static final int PORT = 7777;

  // Frame header: magic(4) + width(2) + height(2) + dataLen(4) = 12 bytes
  // JPEG = JPEG-compressed GL framebuffer
  public static final byte[] MAGIC_JPEG  = {'J', 'P', 'E', 'G'};
  public static final byte[] MAGIC_RGBA  = {'R', 'G', 'B', 'A'}; // kept for compat
  public static final byte[] MAGIC_DEPTH = {'D', 'P', 'T', 'H'}; // kept for compat
  private static final byte[] MAGIC = MAGIC_JPEG;
  private static final int HEADER_SIZE = 12;
  private static final int JPEG_QUALITY = 85;

  private ServerSocket serverSocket;
  private Socket clientSocket;
  private OutputStream outputStream;
  private Thread acceptThread;
  private volatile boolean running = false;

  // Prevents blocking the camera thread if the network can't keep up
  private final AtomicBoolean framePending = new AtomicBoolean(false);
  private Thread sendThread;
  private volatile byte[] pendingData;
  private volatile int pendingWidth, pendingHeight;

  // Reusable objects for JPEG encoding (only touched by sendThread)
  private Bitmap streamBitmap;
  private byte[] rowFlipBuf;
  private final ByteArrayOutputStream jpegBuf = new ByteArrayOutputStream(256 * 1024);

  public void start() throws IOException {
    serverSocket = new ServerSocket(PORT);
    running = true;

    // Thread that waits for and sends queued frames
    sendThread = new Thread(() -> {
      while (running) {
        if (framePending.get()) {
          byte[] data = pendingData;
          int w = pendingWidth;
          int h = pendingHeight;
          framePending.set(false);
          doSend(w, h, data);
        } else {
          try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
      }
    });
    sendThread.setDaemon(true);
    sendThread.start();

    // Thread that accepts incoming client connections
    acceptThread = new Thread(() -> {
      while (running) {
        try {
          Socket s = serverSocket.accept();
          synchronized (this) {
            disconnectClient();
            clientSocket = s;
            outputStream = s.getOutputStream();
          }
          Log.d(TAG, "Client connected: " + s.getInetAddress());
        } catch (IOException e) {
          if (running) Log.e(TAG, "Accept error: " + e.getMessage());
        }
      }
    });
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  public void stop() {
    running = false;
    try {
      if (serverSocket != null) serverSocket.close();
    } catch (IOException ignored) {}
    synchronized (this) { disconnectClient(); }
    serverSocket = null;
  }

  public boolean isRunning() { return running; }

  public boolean hasClient() {
    return outputStream != null;
  }

  /** Called from the camera thread — queues the frame, never blocks. */
  public void queueFrame(int width, int height, byte[] data) {
    if (!running || outputStream == null) return;
    pendingWidth = width;
    pendingHeight = height;
    pendingData = data;
    framePending.set(true);
  }

  private void doSend(int width, int height, byte[] data) {
    OutputStream os;
    synchronized (this) { os = outputStream; }
    if (os == null) return;

    // --- flip rows in-place (GL origin is bottom-left) ---
    int rowBytes = width * 4;
    if (rowFlipBuf == null || rowFlipBuf.length < rowBytes) rowFlipBuf = new byte[rowBytes];
    for (int top = 0, bot = height - 1; top < bot; top++, bot--) {
      System.arraycopy(data, top * rowBytes, rowFlipBuf, 0, rowBytes);
      System.arraycopy(data, bot * rowBytes, data, top * rowBytes, rowBytes);
      System.arraycopy(rowFlipBuf, 0, data, bot * rowBytes, rowBytes);
    }

    // --- RGBA → Bitmap → JPEG ---
    if (streamBitmap == null || streamBitmap.getWidth() != width || streamBitmap.getHeight() != height) {
      if (streamBitmap != null) streamBitmap.recycle();
      streamBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }
    streamBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data));
    jpegBuf.reset();
    streamBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegBuf);
    byte[] jpeg = jpegBuf.toByteArray();

    try {
      ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
      header.put(MAGIC);
      header.putShort((short) width);
      header.putShort((short) height);
      header.putInt(jpeg.length);
      os.write(header.array());
      os.write(jpeg);
      os.flush();
    } catch (IOException e) {
      Log.d(TAG, "Client disconnected during send");
      synchronized (this) { disconnectClient(); }
    }
  }

  private void disconnectClient() {
    outputStream = null;
    try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
    clientSocket = null;
  }

  /** Returns a human-readable list of addresses clients can connect to. */
  public static String getLocalAddresses() {
    StringBuilder sb = new StringBuilder();
    try {
      for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!iface.isUp() || iface.isLoopback()) continue;
        String name = iface.getName();
        // wlan = WiFi, rndis/usb = USB tethering
        if (!name.startsWith("wlan") && !name.startsWith("rndis") && !name.startsWith("usb")) continue;
        for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
          if (addr.isLoopbackAddress() || addr.getHostAddress().contains(":")) continue;
          if (sb.length() > 0) sb.append("\n");
          sb.append(name).append(": ").append(addr.getHostAddress()).append(":").append(PORT);
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "Could not enumerate interfaces", e);
    }
    if (sb.length() == 0) sb.append("No network interfaces found");
    sb.append("\n\nUSB (adb): adb forward tcp:").append(PORT).append(" tcp:").append(PORT);
    return sb.toString();
  }
}
