package com.example.elf32runner;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.elf32runner.engine.Runner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 主界面：选择 32 位 ELF 文件 → 翻译执行 → 显示输出。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;

    private TextView txtOutput;
    private TextView txtFile;
    private Button btnRun;
    private byte[] selectedElf;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtOutput = findViewById(R.id.txtOutput);
        txtFile = findViewById(R.id.txtFile);
        btnRun = findViewById(R.id.btnRun);

        findViewById(R.id.btnPick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQ_PICK);
            }
        });

        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedElf == null) return;
                runElf(selectedElf);
            }
        });

        findViewById(R.id.btnRunSample).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runElf(SampleProgram.HELLO_ARM32);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = getContentResolver().openInputStream(uri);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        in.close();
                        final byte[] bytes = bos.toByteArray();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                selectedElf = bytes;
                                txtFile.setText("已选择 " + bytes.length + " 字节");
                                btnRun.setEnabled(true);
                            }
                        });
                    } catch (Exception e) {
                        final String msg = e.getMessage();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                txtOutput.setText("读取文件失败：" + msg);
                            }
                        });
                    }
                }
            }).start();
        }
    }

    /** 在后台线程执行 ELF，结果回主线程显示。 */
    private void runElf(final byte[] elf) {
        txtOutput.setText("执行中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                PrintStream out = new PrintStream(bos, true);
                final String result = Runner.run(elf, out, out);
                out.flush();
                final String stdout = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        txtOutput.setText((stdout.isEmpty() ? "" : "程序输出：\n" + stdout) + result);
                    }
                });
            }
        }).start();
    }
}
