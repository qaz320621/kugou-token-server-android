package com.chumc.tokenserver;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {

    private TokenHttpServer server;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText;
    private TextView addressText;
    private Button startBtn;
    private Button stopBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        statusText = findViewById(R.id.statusText);
        addressText = findViewById(R.id.addressText);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);

        startBtn.setOnClickListener(v -> startServer());
        stopBtn.setOnClickListener(v -> stopServer());

        // 状态默认：已停止（手动点按钮启动）
        updateStatus(false);

        // adb 后门：adb shell am start ... --ez auto_start true 可免点击启动服务
        if (getIntent().getBooleanExtra("auto_start", false)) {
            startServer();
        }
    }

    private void startServer() {
        if (server != null) {
            return;
        }
        try {
            TokenHttpServer s = new TokenHttpServer(this);
            s.start(NanoTimeoutHolder.TIMEOUT);
            server = s;
            updateStatus(true);
            Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
            updateStatus(false);
            Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateStatus(boolean running) {
        if (running) {
            statusText.setText("● 运行中");
            statusText.setTextColor(0xFF2E7D32);
            String ip = getLocalIpAddress();
            addressText.setText("本机访问: http://127.0.0.1:" + TokenHttpServer.PORT + "/\n局域网访问: http://" + ip + ":" + TokenHttpServer.PORT + "/");
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        } else {
            statusText.setText("○ 已停止");
            statusText.setTextColor(0xFFC62828);
            addressText.setText("点击下方按钮启动服务");
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        }
    }

    /** 获取本机局域网 IPv4 地址 */
    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress ia = addrs.nextElement();
                    if (ia instanceof Inet4Address && !ia.isLoopbackAddress()) {
                        return ia.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }

    /** NanoHTTPD start 超时参数（毫秒） */
    private static class NanoTimeoutHolder {
        static final int TIMEOUT = 5000;
    }
}
