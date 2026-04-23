package com.example.pipelineinspection;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
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

import android.util.Log;

public class UploadActivity extends AppCompatActivity {

    // 请求码：拍照
    private static final int TAKE_PHOTO = 1;

    private ImageView imgPhoto;
    private EditText etDesc;
    private TextView tvPredict;
    private Button btnTakePhoto;
    private Button btnUpload;

    // 照片 Uri & Bitmap
    private Uri imageUri;
    private Bitmap currentBitmap;

    // ✅ OkHttpClient（上传用）
    private OkHttpClient httpClient;

    // ✅ 你的后端地址（真机局域网要改成电脑 IP）
    // 模拟器访问电脑：10.0.2.2
    // 真机访问电脑：改成 192.168.x.x
    private static final String BASE_URL = "http://10.0.2.2:8080";
    private static final String UPLOAD_API = "/api/upload/image";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ⚠️ 你现在用的是 activity_main，如果你专门做上传页建议换成 activity_upload
        setContentView(R.layout.activity_main);

        // 绑定控件（对应你 activity_main.xml 里的 id）
        imgPhoto = findViewById(R.id.img_photo);
        etDesc = findViewById(R.id.et_desc);
        tvPredict = findViewById(R.id.predict);
        btnTakePhoto = findViewById(R.id.btn_take_photo);
        btnUpload = findViewById(R.id.btn_upload);


        // 初始化 OkHttpClient
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        // 拍照按钮
        btnTakePhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCamera();
            }
        });

        // 上传按钮
        btnUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d("UPLOAD", "click upload, imageUri=" + imageUri +
                        ", bitmapNull=" + (currentBitmap == null));
                if (imageUri == null || currentBitmap == null) {
                    Toast.makeText(UploadActivity.this, "请先拍照", Toast.LENGTH_SHORT).show();
                    return;
                }

                String desc = etDesc.getText().toString().trim();
                uploadPhoto(imageUri, desc);
            }
        });
    }

    /**
     * 调用系统相机拍照
     */
    private void openCamera() {
        String filename = "capture_" + System.currentTimeMillis() + ".jpg";
        File outputImage = new File(getExternalCacheDir(), filename);

        try {
            if (outputImage.exists()) outputImage.delete();
            outputImage.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "创建照片文件失败", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= 24) {
            // authorities 必须和 Manifest 里 provider 一致
            imageUri = FileProvider.getUriForFile(
                    UploadActivity.this,
                    "com.example.pipelineinspection.fileprovider",
                    outputImage
            );
        } else {
            imageUri = Uri.fromFile(outputImage);
        }

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);

        // ✅ 很关键：授予相机 App 对 uri 的读写权限（否则部分机型拍完拿不到图）
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        startActivityForResult(intent, TAKE_PHOTO);
    }

    /**
     * 拍照完成后的回调：显示图片，并保存到 currentBitmap 里
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == TAKE_PHOTO && resultCode == RESULT_OK) {
            try {
                InputStream is = getContentResolver().openInputStream(imageUri);
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                if (is != null) is.close();

                if (bitmap == null) {
                    Toast.makeText(this, "照片解码失败", Toast.LENGTH_SHORT).show();
                    return;
                }

                imgPhoto.setImageBitmap(bitmap);   // 显示到界面
                currentBitmap = bitmap;            // 保存下来，上传用
                tvPredict.setText("拍照成功，待上传...");

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "读取照片失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * ✅ 上传图片 + 文字到服务器（Multipart）
     */
    private void uploadPhoto(Uri photoUri, String desc) {
        final long t0 = System.currentTimeMillis();

        tvPredict.setText("正在上传...");



        byte[] jpegBytes;
        try {
            // ✅ 方式1：直接从 Uri 读取字节（推荐，最稳定）
            jpegBytes = readAllBytesFromUri(photoUri);
            Log.d("UPLOAD", "jpegBytes.length=" + (jpegBytes == null ? -1 : jpegBytes.length)); // ✅ 验证点B：字节数
            runOnUiThread(() -> tvPredict.append("\njpegBytes=" + (jpegBytes == null ? -1 : jpegBytes.length)));

            if (jpegBytes == null || jpegBytes.length == 0) {
                Toast.makeText(this, "读取图片数据失败", Toast.LENGTH_SHORT).show();
                tvPredict.setText("读取图片失败");
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            tvPredict.setText("读取图片失败: " + e.getMessage());
            return;
        }

        // 组装 multipart
        RequestBody fileBody = RequestBody.create(
                jpegBytes,
                MediaType.parse("image/jpeg")
        );

        String safeName = (desc == null || desc.isBlank())
                ? ("photo_" + System.currentTimeMillis())
                : desc.replaceAll("[\\\\/:*?\"<>|]", "_"); // 替换非法字符
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // ✅ 后端一般用 @RequestParam("file") 接收，所以字段名必须叫 file
                .addFormDataPart("file", "photo.jpg", fileBody)
                // 你想一起传的文字说明
                .addFormDataPart("note", desc == null ? "" : desc)
                .addFormDataPart("displayName", safeName)
                .build();

        String url = ServerConfig.httpBase(this) + "/api/upload/image";
        Log.d("UPLOAD", "uploadUrl=" + url);
        runOnUiThread(() -> tvPredict.setText("uploadUrl=" + url));

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                long t1 = System.currentTimeMillis();
                runOnUiThread(() -> {
                    tvPredict.setText("上传失败: " + e.getMessage() + "\n耗时=" + (t1 - t0) + "ms");
                    Toast.makeText(UploadActivity.this, "上传失败", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                long t1 = System.currentTimeMillis();
                String body = response.body() != null ? response.body().string() : "";

                runOnUiThread(() -> {
                    if (!response.isSuccessful()) {
                        tvPredict.setText("上传失败 code=" + response.code() +
                                "\nbody=" + body +
                                "\n耗时=" + (t1 - t0) + "ms");
                        return;
                    }

                    // ✅ 尝试解析返回 JSON 的 id
                    Long id = parseId(body);

                    tvPredict.setText(
                            "上传成功 ✅\n" +
                                    "耗时=" + (t1 - t0) + "ms\n" +
                                    "itemId=" + (id != null ? id : "null") + "\n" +
                                    "response=" + body
                    );

                    Toast.makeText(UploadActivity.this, "上传成功", Toast.LENGTH_SHORT).show();

                    // ✅ 如果你后面要自动识别：拿到 id 后发 WS 指令即可
                    // 例如：send WS {"type":"DETECT_AND_MATCH","data":{"targetItemId":id}}
                });
            }
        });
    }

    private byte[] readAllBytesFromUri(Uri uri) throws IOException {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toByteArray();
    }

    private Long parseId(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("id")) return obj.getLong("id");
        } catch (Exception ignored) {}
        return null;
    }
}
