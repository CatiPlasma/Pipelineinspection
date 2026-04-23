package com.example.pipelineinspection;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Button btnGoUpload;
    private Button btnGoMatch;
    private Button btnGoTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 使用我们刚建的菜单布局
        setContentView(R.layout.activity_home);

        btnGoUpload = findViewById(R.id.btn_go_upload);
        btnGoMatch = findViewById(R.id.btn_go_match);
        btnGoTest  = findViewById(R.id.btn_go_test);

        // 按钮 1：跳转到拍照上传界面
        btnGoUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this,UploadActivity.class);
                startActivity(intent);
            }
        });

        // 按钮 2：跳转到目标匹配界面
        btnGoMatch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, MatchActivity.class);
                startActivity(intent);
            }
        });
// 跳转通信测试界面（SocketTestActivity）
        btnGoTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, SocketTestActivity.class);
                startActivity(intent);
            }
        });
    }
}