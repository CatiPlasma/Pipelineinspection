package com.example.pipelineinspection;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private EditText etHost, etPort;
    private Button btnSave;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etHost = findViewById(R.id.et_host);
        etPort = findViewById(R.id.et_port);
        btnSave = findViewById(R.id.btn_save);

        // 回显当前保存的配置
        etHost.setText(ServerConfig.getHost(this));
        etPort.setText(String.valueOf(ServerConfig.getPort(this)));

        btnSave.setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();

            if (TextUtils.isEmpty(host)) {
                Toast.makeText(this, "host 不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            int port = 8080;
            if (!TextUtils.isEmpty(portStr)) {
                port = Integer.parseInt(portStr);
            }

            ServerConfig.set(this, host, port);
            Toast.makeText(this, "保存成功：" + host + ":" + port, Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}

