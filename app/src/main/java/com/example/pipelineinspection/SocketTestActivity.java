package com.example.pipelineinspection;


import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import android.content.Intent;




public class SocketTestActivity extends AppCompatActivity {

    private EditText etServerUrl;
    private EditText etEchoText;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnSendPing;
    private Button btnSendEcho;
    private TextView tvLog;

    // === 新增：选图/上传相关 UI ===
    private Button btnPickImage;
    private Button btnUploadImage;
    private TextView tvImageUri;

    private OkHttpClient client;
    private WebSocket webSocket;

    // === 新增：保存选择的图片 ===
    private Uri selectedImageUri;

    // === 新增：系统选图 launcher ===
    private ActivityResultLauncher<String> pickImageLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_socket_test);

        etServerUrl = findViewById(R.id.et_server_url);
        etEchoText = findViewById(R.id.et_echo_text);
        btnConnect = findViewById(R.id.btn_connect);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        btnSendPing = findViewById(R.id.btn_send_ping);
        btnSendEcho = findViewById(R.id.btn_send_echo);
        tvLog = findViewById(R.id.tv_log);

        // 新增 UI
        btnPickImage = findViewById(R.id.btn_pick_image);
        btnUploadImage = findViewById(R.id.btn_upload_image);
        tvImageUri = findViewById(R.id.tv_image_uri);

        tvLog.setMovementMethod(new ScrollingMovementMethod());

        Button btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(SocketTestActivity.this, SettingsActivity.class));
        });

        // ✅ 注意：真机测试不要用 10.0.2.2
        // 真机：ws://电脑局域网IP:8080/ws
        // 模拟器：ws://10.0.2.2:8080/ws
        etServerUrl.setText(ServerConfig.wsUrl(this));
        if (!ServerConfig.isConfigured(this)) {
            appendLog("首次使用请先设置服务器IP");
            startActivity(new Intent(this, SettingsActivity.class));
        }


        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket readTimeout 建议 0
                .build();

        // === 选图 launcher ===
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        selectedImageUri = uri;
                        tvImageUri.setText("已选择: " + uri.toString());
                        appendLog("选择图片成功: " + uri);
                    } else {
                        appendLog("未选择图片");
                    }
                }
        );

        // Connect
        btnConnect.setOnClickListener(v -> connectWebSocket());

        // Disconnect
        btnDisconnect.setOnClickListener(v -> disconnectWebSocket());

        // Send PING
        btnSendPing.setOnClickListener(v -> {
            String jsonPing = "{\"type\":\"PING\"}";
            sendText(jsonPing);
        });

        // Send ECHO
        btnSendEcho.setOnClickListener(v -> {
            String text = etEchoText.getText().toString().trim();
            if (text.isEmpty()) {
                appendLog("ECHO 文本为空，不发送");
            } else {
                String safe = text.replace("\\", "\\\\").replace("\"", "\\\"");
                String jsonEcho = "{\"type\":\"ECHO\",\"data\":{\"msg\":\"" + safe + "\"}}";
                sendText(jsonEcho);
            }
        });

        // ✅ 新增：选择图片按钮
        btnPickImage.setOnClickListener(v -> {
            pickImageLauncher.launch("image/*");
        });

        // ✅ 新增：上传图片按钮（HTTP Multipart）
        btnUploadImage.setOnClickListener(v -> {
            if (selectedImageUri == null) {
                appendLog("请先选择图片，再上传");
                return;
            }
            uploadSelectedImage();
        });
    }

    // ===============================
    // WebSocket 连接
    // ===============================
    private void connectWebSocket() {
        if (webSocket != null) {
            appendLog("已经连接，无需重复连接");
            return;
        }

        String url = ServerConfig.wsUrl(this);
        etServerUrl.setText(url); // 顺便回显

        if (url.isEmpty()) {
            appendLog("ServerUrl 为空");
            return;
        }

        appendLog("正在连接: " + url);
        Request request = new Request.Builder().url(url).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                appendLog("连接成功");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                appendLog("收到文本消息: " + text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                appendLog("收到二进制消息: " + bytes.hex());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                appendLog("连接即将关闭: code=" + code + ", reason=" + reason);
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                appendLog("连接已关闭: code=" + code + ", reason=" + reason);
                SocketTestActivity.this.webSocket = null;
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                appendLog("连接失败: " + t.getMessage());
                SocketTestActivity.this.webSocket = null;
            }
        });
    }

    private void disconnectWebSocket() {
        if (webSocket != null) {
            appendLog("主动关闭连接");
            webSocket.close(1000, "client close");
            webSocket = null;
        } else {
            appendLog("当前没有连接");
        }
    }

    private void sendText(String text) {
        if (webSocket == null) {
            appendLog("还未连接，无法发送: " + text);
            return;
        }
        boolean result = webSocket.send(text);
        if (result) appendLog("发送: " + text);
        else appendLog("发送失败: " + text);
    }

    // ===============================
    // ✅ 图片上传（HTTP multipart）
    // ===============================
    private void uploadSelectedImage() {
        // 1) 从 ws://host:port/ws 转成 http://host:port
        String wsUrl = etServerUrl.getText().toString().trim();
        String httpBase = wsToHttpBase(wsUrl);
        if (httpBase == null) {
            appendLog("无法从 WS 地址转换 HTTP 地址，请检查: " + wsUrl);
            return;
        }

        String uploadUrl = httpBase + "/api/upload/image";
        appendLog("上传地址: " + uploadUrl);

        long clientStartTs = System.currentTimeMillis();

        try {
            // 2) 读取图片 bytes
            byte[] bytes = readAllBytesFromUri(selectedImageUri);
            if (bytes == null || bytes.length == 0) {
                appendLog("读取图片失败或为空");
                return;
            }

            // 3) 文件名 & contentType
            String fileName = queryDisplayName(selectedImageUri);
            if (fileName == null) fileName = "upload.jpg";

            String contentType = getContentResolver().getType(selectedImageUri);
            if (contentType == null) contentType = "image/jpeg";

            RequestBody fileBody = RequestBody.create(bytes, MediaType.parse(contentType));

            MultipartBody reqBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, fileBody)
                    .addFormDataPart("note", "from_socket_test")
                    .build();

            Request request = new Request.Builder()
                    .url(uploadUrl)
                    .post(reqBody)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    appendLog("上传失败: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    long clientEndTs = System.currentTimeMillis();
                    long clientTotalMs = clientEndTs - clientStartTs;

                    String body = response.body() != null ? response.body().string() : "";
                    appendLog("上传返回 code=" + response.code());
                    appendLog("上传耗时(客户端) ms=" + clientTotalMs);
                    appendLog("返回内容: " + body);

                    // 4) 可选：解析 JSON 拿 id，然后自动触发识别（走 WS）
                    // 你后端如果返回 {"id":12,...}
                    Long id = parseIdFromJson(body);
                    if (id != null) {
                        appendLog("上传成功，得到 itemId=" + id);

                        // ✅ 可选：自动触发识别
                        // 如果你希望上传后自动识别，把这行打开：
                        // sendDetectAndMatch(id);
                    }
                }
            });

        } catch (Exception e) {
            appendLog("上传异常: " + e.getMessage());
        }
    }

    // 上传成功后自动发送 DETECT_AND_MATCH（可选）
    private void sendDetectAndMatch(long targetItemId) {
        // 注意：需要 WS 已连接
        if (webSocket == null) {
            appendLog("WS 未连接，无法发送 DETECT_AND_MATCH");
            return;
        }
        String json = "{\"type\":\"DETECT_AND_MATCH\",\"data\":{\"targetItemId\":" + targetItemId + "}}";
        sendText(json);
    }

    // ===============================
    // 工具方法
    // ===============================
    private String wsToHttpBase(String wsUrl) {
        // ws://192.168.1.23:8080/ws  -> http://192.168.1.23:8080
        // wss://... -> https://...
        if (wsUrl == null) return null;

        String u = wsUrl.trim();
        if (u.startsWith("ws://")) {
            u = "http://" + u.substring("ws://".length());
        } else if (u.startsWith("wss://")) {
            u = "https://" + u.substring("wss://".length());
        } else {
            return null;
        }

        // 去掉路径 /ws
        int idx = u.indexOf("/", "http://".length());
        if (idx > 0) {
            // 这里把 http://host:port/ws 的 /ws 去掉
            return u.substring(0, idx);
        }
        return u;
    }

    private byte[] readAllBytesFromUri(Uri uri) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private Long parseIdFromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("id")) return obj.getLong("id");
        } catch (Exception ignored) {}
        return null;
    }

    private void appendLog(final String msg) {
        runOnUiThread(() -> {
            tvLog.append(msg + "\n");

            final int scrollAmount = tvLog.getLayout() == null
                    ? 0
                    : tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scrollAmount > 0) tvLog.scrollTo(0, scrollAmount);
            else tvLog.scrollTo(0, 0);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webSocket != null) {
            webSocket.close(1000, "activity destroy");
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
    }
}
