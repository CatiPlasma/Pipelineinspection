package com.example.pipelineinspection;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MatchActivity extends AppCompatActivity {

    private ImageView ivTarget;
    private ImageView ivMonitor;
    private Button btnSelectTarget;
    private Button btnStartMatch;   // 现在作为“开始抓拍/停止抓拍”
    private TextView tvStatus;

    private OkHttpClient httpClient;

    private OkHttpClient wsClient;
    private WebSocket webSocket;

    private Long targetItemId = null; // 目前不用于抓拍，但保留
    private final List<DbItem> imageItems = new ArrayList<>();

    private volatile boolean streaming = false;

    // ✅ 抓拍间隔（秒）
    private static final int SNAP_INTERVAL_SEC = 5;

    // 状态文字节流（避免频繁 setText；抓拍模式其实不会频繁）
    private long lastStatusUiTs = 0;

    static class DbItem {
        long id;
        String fileName;
        String contentType;
        String createdAt;
        Long fileSize;

        @Override
        public String toString() {
            String name = (fileName == null || fileName.isBlank()) ? ("item-" + id) : fileName;
            String size = (fileSize == null) ? "" : (" | " + fileSize + "B");
            String time = (createdAt == null) ? "" : (" | " + createdAt);
            return id + " | " + name + size + time;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_match);

        ivTarget = findViewById(R.id.iv_target);
        ivMonitor = findViewById(R.id.iv_monitor);
        btnSelectTarget = findViewById(R.id.btn_select_target);
        btnStartMatch = findViewById(R.id.btn_start_match);
        tvStatus = findViewById(R.id.tv_status);

        setStatus("先连接后端，然后点击“开始抓拍”（每 5 秒回传 1 张）", Color.GRAY);
        btnStartMatch.setText("开始抓拍");

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        // ✅ 加 pingInterval，更稳
        wsClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build();

        connectWsIfNeeded();

        // 选择目标：仍保留（你后面做匹配会用到；现在抓拍不依赖它）
        btnSelectTarget.setOnClickListener(v -> {
            connectWsIfNeeded();
            if (webSocket == null) {
                setStatus("WS 未连接，稍后再试", Color.RED);
                return;
            }
            setStatus("正在加载数据库图片列表...", Color.parseColor("#FF9800"));
            sendWs("LIST_DB", null);
        });

        // ✅ 开始抓拍/停止抓拍（toggle）
        btnStartMatch.setOnClickListener(v -> {
            connectWsIfNeeded();
            if (webSocket == null) {
                setStatus("WS 未连接，无法开始抓拍", Color.RED);
                return;
            }

            if (!streaming) {
                streaming = true;
                btnStartMatch.setText("停止抓拍");
                setStatus("启动抓拍中...（每 " + SNAP_INTERVAL_SEC + " 秒 1 张）", Color.parseColor("#FF9800"));

                // 先启动相机
                sendWs("START_CAMERA", new JSONObject());

                // ✅ 关键：用 intervalSec 模式
                JSONObject streamData = new JSONObject();
                try { streamData.put("intervalSec", SNAP_INTERVAL_SEC); } catch (Exception ignored) {}
                sendWs("START_STREAM", streamData);

                // 拉起detect，确保传出的id不为null
                JSONObject detectData = new JSONObject();
                try { detectData.put("targetItemId", targetItemId); } catch (Exception ignored) {}
                if (targetItemId!=null) sendWs("DETECT_AND_MATCH", detectData);

            } else {
                streaming = false;
                btnStartMatch.setText("开始抓拍");
                sendWs("STOP_STREAM", new JSONObject());
                setStatus("抓拍已停止", Color.GRAY);
            }
        });
    }

    // ---------------- WebSocket ----------------

    private void connectWsIfNeeded() {
        if (webSocket != null) return;

        String wsUrl = ServerConfig.wsUrl(this);
        Request request = new Request.Builder().url(wsUrl).build();

        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                runOnUiThread(() -> setStatus("WS 已连接 ✅", Color.GRAY));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleWsText(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, @Nullable Response response) {
                webSocket = null;
                runOnUiThread(() -> {
                    streaming = false;
                    btnStartMatch.setText("开始抓拍");
                    setStatus("WS 连接失败: " + t.getMessage(), Color.RED);
                });
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                webSocket = null;
                runOnUiThread(() -> {
                    streaming = false;
                    btnStartMatch.setText("开始抓拍");
                    setStatus("WS 已关闭: " + reason, Color.RED);
                });
            }
        });
    }

    private void sendWs(String type, @Nullable JSONObject data) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", type);
            if (data != null) obj.put("data", data);
            if (webSocket != null) webSocket.send(obj.toString());
        } catch (Exception ignored) {}
    }

    // ---------------- Receive/Route ----------------

    private void handleWsText(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            String type = obj.optString("type", "");
            JSONObject data = obj.optJSONObject("data");

            switch (type) {
                case "DB_ITEMS": {
                    JSONArray arr = (data != null) ? data.optJSONArray("items") : null;

                    List<DbItem> list = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject it = arr.getJSONObject(i);

                            DbItem di = new DbItem();
                            di.id = it.optLong("id");
                            di.fileName = it.optString("fileName", "");
                            di.contentType = it.optString("contentType", "");
                            di.createdAt = it.optString("createdAt", null);
                            if (it.has("fileSize")) di.fileSize = it.optLong("fileSize");

                            if (di.contentType != null && di.contentType.startsWith("image/")) {
                                list.add(di);
                            }
                        }
                    }

                    imageItems.clear();
                    imageItems.addAll(list);
                    runOnUiThread(this::showPickDialog);
                    break;
                }

                case "STREAM_FRAME": {
                    // ✅ 抓拍模式也复用 STREAM_FRAME（后端会带 mode=interval）
                    if (!streaming || data == null) break;

                    String b64 = data.optString("jpegBase64", null);
                    if (b64 == null || b64.isBlank()) break;

                    byte[] jpeg = Base64.decode(b64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (bmp == null) break;

                    // TODO: Add annotates to me if annotates added by Python can't satisfy; if need add here, only add to succeeded one
                    runOnUiThread(() -> ivMonitor.setImageBitmap(bmp));

                    // 状态提示（抓拍不会频繁，但仍避免过于频繁 setText）
                    long now = System.currentTimeMillis();
                    if (now - lastStatusUiTs > 800) {
                        lastStatusUiTs = now;
                        int len = b64.length();
                        String mode = data.optString("mode", "");
                        int intervalSec = data.optInt("intervalSec", SNAP_INTERVAL_SEC);
                        runOnUiThread(() ->
                                tvStatus.setText("抓拍中... mode=" + mode + " intervalSec=" + intervalSec + " b64len=" + len));
                    }
                    break;
                }

                case "ERROR": {
                    runOnUiThread(() -> {
                        String msg = (data != null) ? data.toString() : text;
                        setStatus("后端 ERROR: " + msg, Color.RED);
                        Toast.makeText(this, "后端错误，请看状态栏", Toast.LENGTH_SHORT).show();
                    });
                    break;
                }

                // FIXME: ACK is not standard type; Debug output, delete me before release!
                case "ACK": {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Recived ACK", Toast.LENGTH_SHORT).show();
                    });
                }

                // TODO: DETECTION_RESULT and MATCH_SCORE response
                case "DETECTION_RESULT": {
                    break;
                }

                case "MATCH_SCORE": {
                    break;
                }

                // TODO: Add a window to tell client here is what he/she want
                case "MATCH_SUCCESS": {
                    long targetId = data.optLong("targetItemId");
                    int matchedBoxIndex = data.optInt("boxIndex");
                    double score = data.optDouble("detScore"),
                            similarity = data.optDouble("similarity");
                    String msg = "发现"+targetId+"的最佳匹配项，相似度得分为"+similarity;
                    break;
                }

                default:
                    if ("CAMERA_STATUS".equals(type) && data != null) {
                        boolean ok = data.optBoolean("ok", false);
                        runOnUiThread(() -> setStatus(ok ? "相机已启动 ✅" : ("相机启动失败: " + data.optString("error")),
                                ok ? Color.GRAY : Color.RED));
                    }
                    break;
            }
        } catch (Exception ignored) {}
    }

    // ---------------- DB pick/preview (保留) ----------------

    private void showPickDialog() {
        if (imageItems.isEmpty()) {
            setStatus("数据库中没有图片条目（请先上传）", Color.RED);
            return;
        }

        String[] labels = new String[imageItems.size()];
        for (int i = 0; i < imageItems.size(); i++) labels[i] = imageItems.get(i).toString();

        new AlertDialog.Builder(this)
                .setTitle("选择数据库中的目标图片（可选）")
                .setItems(labels, (dialog, which) -> {
                    DbItem picked = imageItems.get(which);
                    targetItemId = picked.id;

                    setStatus("已选择 targetItemId=" + targetItemId + "，加载预览中...",
                            Color.parseColor("#FF9800"));

                    loadTargetPreview(targetItemId);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadTargetPreview(long itemId) {
        String url = ServerConfig.httpBase(this) + "/api/items/" + itemId + "/image";

        Request req = new Request.Builder().url(url).get().build();
        httpClient.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> setStatus("加载目标预览失败: " + e.getMessage(), Color.RED));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> setStatus("加载目标预览失败 code=" + response.code(), Color.RED));
                    return;
                }
                byte[] bytes = response.body() != null ? response.body().bytes() : null;
                if (bytes == null || bytes.length == 0) {
                    runOnUiThread(() -> setStatus("目标预览为空", Color.RED));
                    return;
                }

                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                runOnUiThread(() -> {
                    ivTarget.setImageBitmap(bmp);
                    setStatus("目标预览加载完成 ✅ targetItemId=" + itemId, Color.parseColor("#4CAF50"));
                });
            }
        });
    }

    // ---------------- UI ----------------

    private void setStatus(String msg, int color) {
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(msg);
        tvStatus.setTextColor(color);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (webSocket != null) {
                // ✅ 停止抓拍
                sendWs("STOP_STREAM", new JSONObject());
                webSocket.close(1000, "activity destroy");
                webSocket = null;
            }
        } catch (Exception ignored) {}

        streaming = false;
    }
}