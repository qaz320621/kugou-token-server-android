package com.chumc.tokenserver;

import android.content.Intent;
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

        // App 打开即启动 Token 服务（后台常驻；不做开机自启）
        startServer();
    }

    private void startServer() {
        startService(new Intent(this, TokenService.class));
        Toast.makeText(this, "Token 服务启动中", Toast.LENGTH_SHORT).show();
        // Service 的 running 标志是异步设置的，延迟刷新保证按钮状态实时更新
        handler.postDelayed(this::refreshStatus, 800);
    }

    private void stopServer() {
        stopService(new Intent(this, TokenService.class));
        Toast.makeText(this, "Token 服务已停止", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::refreshStatus, 800);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (TokenService.running) {
            statusText.setText("● 运行中（后台常驻）");
            statusText.setTextColor(0xFF2E7D32);
            String ip = getLocalIpAddress();
            addressText.setText("本机: http://127.0.0.1:" + TokenHttpServer.PORT + "/\n局域网: http://" + ip + ":" + TokenHttpServer.PORT + "/");
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
}
