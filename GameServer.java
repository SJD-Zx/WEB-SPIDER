package com.webslinger.game;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny HTTP + WebSocket server: serves the game page and relays player positions. */
public class GameServer {
  static class Client {
    int id;
    int hue;
    Socket sock;
    OutputStream out;
    volatile String state;
  }

  private final Context ctx;
  private final int port;
  private ServerSocket server;
  private volatile boolean running;
  private final List<Client> clients = new CopyOnWriteArrayList<>();
  private int nextId = 1;
  private final Random rnd = new Random();

  GameServer(Context c, int port) {
    this.ctx = c.getApplicationContext();
    this.port = port;
  }

  synchronized void start() throws IOException {
    if (running) return;
    server = new ServerSocket();
    server.setReuseAddress(true);
    server.bind(new InetSocketAddress(port));
    running = true;
    new Thread(this::acceptLoop).start();
    new Thread(this::broadcastLoop).start();
  }

  synchronized void stop() {
    running = false;
    try { if (server != null) server.close(); } catch (IOException e) { }
    for (Client c : clients) {
      try { c.sock.close(); } catch (IOException e) { }
    }
    clients.clear();
  }

  static String ips() {
    JSONArray arr = new JSONArray();
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        String n = ni.getName();
        if (!ni.isUp() || ni.isLoopback()) continue;
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") || n.startsWith("dummy") || n.startsWith("tun")) continue;
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
          if (a instanceof Inet4Address && a.isSiteLocalAddress()) arr.put(a.getHostAddress());
        }
      }
    } catch (Exception e) { }
    return arr.toString();
  }

  private void acceptLoop() {
    while (running) {
      try {
        final Socket s = server.accept();
        new Thread(() -> handle(s)).start();
      } catch (IOException e) {
        if (!running) return;
      }
    }
  }

  private void handle(Socket s) {
    try {
      s.setTcpNoDelay(true);
      InputStream in = s.getInputStream();
      OutputStream out = s.getOutputStream();
      String head = readHead(in);
      if (head == null) { s.close(); return; }
      String[] lines = head.split("\r\n");
      String key = null;
      boolean ws = false;
      for (String line : lines) {
        String l = line.toLowerCase();
        if (l.startsWith("sec-websocket-key:")) key = line.substring(18).trim();
        if (l.startsWith("upgrade:") && l.contains("websocket")) ws = true;
      }
      if (ws && key != null) { websocket(s, in, out, key); return; }
      String[] first = lines[0].split(" ");
      String path = first.length > 1 ? first[1] : "/";
      String asset = path.startsWith("/three.js") ? "three.js" : "index.html";
      byte[] body = readAsset(asset);
      if (body == null) {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        s.close();
        return;
      }
      String type = asset.endsWith(".js") ? "application/javascript" : "text/html; charset=utf-8";
      out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(body);
      out.flush();
      s.close();
    } catch (Exception e) {
      try { s.close(); } catch (IOException x) { }
    }
  }

  private String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    int c, a = 0, b = 0, d = 0, n = 0;
    while ((c = in.read()) != -1 && n++ < 8192) {
      bo.write(c);
      if (a == '\r' && b == '\n' && d == '\r' && c == '\n') return bo.toString("UTF-8");
      a = b; b = d; d = c;
    }
    return null;
  }

  private byte[] readAsset(String name) {
    try (InputStream in = ctx.getAssets().open(name)) {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
      return bo.toByteArray();
    } catch (IOException e) {
      return null;
    }
  }

  private int readByte(InputStream in) throws IOException {
    int b = in.read();
    if (b < 0) throw new IOException("eof");
    return b;
  }

  private void readFully(InputStream in, byte[] buf, int n) throws IOException {
    int o = 0;
    while (o < n) {
      int r = in.read(buf, o, n - o);
      if (r < 0) throw new IOException("eof");
      o += r;
    }
  }

  private void websocket(Socket s, InputStream in, OutputStream out, String key) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-1");
    String acc = Base64.encodeToString(md.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
    Client c = new Client();
    synchronized (this) { c.id = nextId++; }
    c.hue = rnd.nextInt(360);
    c.sock = s;
    c.out = out;
    clients.add(c);
    send(c, "{\"t\":\"hi\",\"id\":" + c.id + ",\"hue\":" + c.hue + "}");
    try {
      while (running) {
        int b0 = in.read();
        if (b0 < 0) break;
        int b1 = readByte(in);
        int op = b0 & 15;
        long len = b1 & 127;
        if (len == 126) {
          len = (readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
          len = 0;
          for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        boolean masked = (b1 & 128) != 0;
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask, 4);
        if (len > 1000000) break;
        byte[] data = new byte[(int) len];
        readFully(in, data, (int) len);
        if (masked) {
          for (int i = 0; i < data.length; i++) data[i] = (byte) (data[i] ^ mask[i & 3]);
        }
        if (op == 8) break;
        if (op == 9) { sendFrame(c, 10, data); continue; }
        if (op == 1) {
          String txt = new String(data, StandardCharsets.UTF_8);
          try {
            JSONObject m = new JSONObject(txt);
            if ("s".equals(m.optString("t"))) c.state = txt;
          } catch (Exception e) { }
        }
      }
    } finally {
      clients.remove(c);
      try { s.close(); } catch (IOException e) { }
    }
  }

  private void send(Client c, String text) {
    sendFrame(c, 1, text.getBytes(StandardCharsets.UTF_8));
  }

  private void sendFrame(Client c, int op, byte[] p) {
    try {
      ByteArrayOutputStream f = new ByteArrayOutputStream();
      f.write(0x80 | op);
      if (p.length < 126) {
        f.write(p.length);
      } else if (p.length < 65536) {
        f.write(126);
        f.write(p.length >> 8);
        f.write(p.length & 255);
      } else {
        f.write(127);
        for (int i = 7; i >= 0; i--) f.write((int) (((long) p.length >> (8 * i)) & 255));
      }
      f.write(p);
      synchronized (c) {
        c.out.write(f.toByteArray());
        c.out.flush();
      }
    } catch (IOException e) {
      clients.remove(c);
    }
  }

  private void broadcastLoop() {
    while (running) {
      try {
        JSONArray arr = new JSONArray();
        for (Client c : clients) {
          if (c.state == null) continue;
          JSONObject o = new JSONObject(c.state);
          o.remove("t");
          o.put("id", c.id);
          o.put("hue", c.hue);
          arr.put(o);
        }
        String msg = "{\"t\":\"w\",\"players\":" + arr.toString() + "}";
        for (Client c : clients) send(c, msg);
      } catch (Exception e) { }
      try { Thread.sleep(50); } catch (InterruptedException e) { return; }
    }
  }
}
