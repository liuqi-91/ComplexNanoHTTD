package com.lx.learngitprojectdemo;

import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {


    public static final String TAG = "NanoHttpD";
    private Handler mHandler = new Handler();

    private WebSocket mWebsocket;
    private Runnable heartBeatRunnable = new Runnable() {
        private long sendTime = System.currentTimeMillis();

        @Override
        public void run() {
            if (System.currentTimeMillis() - sendTime >= 1000) {
                if (mWebsocket == null) {
                    Log.i(TAG, "okHttp mWebsocket == null");
                } else {
                    String message = "我是客户端";
                    mWebsocket.send(message);
                }
                sendTime = System.currentTimeMillis();
            }
            mHandler.postDelayed(this, 1000); //每隔一定的时间，对长连接进行一次心跳检测
        }
    };
    OkHttpClient okHttpClient = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EasyPermissions.requestPermissions(
                this,  //上下文
                "需要拨打电话的权限", //提示文言
                11, //请求码
                "android.permission.INTERNET",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"//权限列表
        );

        ((Button) findViewById(R.id.start_server_btn)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    new MyServer(10177) {

                    }.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        ((Button) findViewById(R.id.start_client_request_btn)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                okHttpClient.newWebSocket(new Request.Builder()
                        .url("ws://127.0.0.1:10177")
                        .build(), new WebSocketListener() {
                    @Override
                    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                        super.onClosed(webSocket, code, reason);
                        mWebsocket = null;
                        Log.i(TAG, "okHttp onClosed code " + code);
                    }

                    @Override
                    public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                        super.onClosing(webSocket, code, reason);
                        Log.i(TAG, "okHttp onClosing " + code);
                    }

                    @Override
                    public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                        super.onFailure(webSocket, t, response);
                        Log.i(TAG, "okHttp onFailure " + t.getMessage());
                    }

                    @Override
                    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                        super.onMessage(webSocket, text);
                        Log.i(TAG, "okHttp onMessage " + text);
                    }

                    @Override
                    public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
                        super.onMessage(webSocket, bytes);
                        Log.i(TAG, "okHttp onMessage " + bytes.toString());
                    }

                    @Override
                    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                        super.onOpen(webSocket, response);
                        mWebsocket = webSocket;
                        Log.i(TAG, "okHttp onOpen " + response.toString());
                    }
                });
                // 开启心跳检测
                mHandler.postDelayed(heartBeatRunnable, 1000);
            }
        });
        ((Button) findViewById(R.id.control_heartBeat_btn)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });

        ((Button) findViewById(R.id.request_file_btn)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {


                okHttpClient.newCall(new Request.Builder()
                        .url("http://127.0.0.1:10177/requestfile")
                        .build()).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NotNull Call call, @NotNull IOException e) {
                        Log.i(TAG, "Nano server onFailure " + e);
                    }

                    @Override
                    public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                        Log.i(TAG, "Nano server response = " + response.code());
                        //单个文件
//                        InputStream fileInputStream = response.body().byteStream();
//                        File newFile = new File("storage/emulated/0/2.mp4");
//                        FileOutputStream fileOutputStream = new FileOutputStream(newFile);
//
//                        byte[] bytes = new byte[64 * 1024];
//
//                        int len = -1;
//                        long total = 0;
//                        while ((len = fileInputStream.read(bytes)) != -1) {
//                            fileOutputStream.write(bytes, 0, len);
//                            total += len;
//                            Log.i(TAG, "okHttp download newFile = " + total + ", len = " + len);
//                        }

                        //多个文件
                        InputStream fileInputStream = response.body().byteStream();
                        ZipInputStream zipInputStream = new ZipInputStream(fileInputStream);
                        File newFile;
                        FileOutputStream fileOutputStream;
                        byte[] bytes = new byte[64 * 1024];
                        ZipEntry zipEntry = zipInputStream.getNextEntry();
                        int nameNum = 10;
                        while ((zipEntry) != null) {

                            ++nameNum;
                            newFile = new File("storage/emulated/0/" + nameNum + ".mp4");
                            if (!newFile.exists()) {
                                newFile.createNewFile();
                            }
                            fileOutputStream = new FileOutputStream(newFile);
                            int len = -1;
                            long total = 0;
                            Log.i(TAG, "okHttp download newFile start = " + zipEntry.getName() + ", len = " + zipEntry.getSize());
                            while ((len = zipInputStream.read(bytes)) != -1) {
                                fileOutputStream.write(bytes, 0, len);
                                total += len;
                            }
                            Log.i(TAG, "okHttp download newFile = " + zipEntry.getName() + ", down len = " + total);

                            zipEntry = zipInputStream.getNextEntry();

                        }
                    }


                });

            }
        });
    }


    public class MyServer extends NanoWSD {
        public WebSocket webSocket;

        public MyServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            if (isWebsocketRequested(session)) {
                return super.serve(session);
            } else {

//                //传输单个文件
//                File file = new File("/storage/emulated/0/1.mp4");
//                try {
//                    Log.i(TAG, "收到okHttp download 的请求");
//                    return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, new FileInputStream(file), file.length());
//                } catch (FileNotFoundException e) {
//                    e.printStackTrace();
//                }
//                return null;
//
                //传输多个文件zip格式
                return newChunkedZipResponse(Response.Status.OK, MIME_PLAINTEXT, new ArrayList<String>() {
                    {
                        add("/storage/emulated/0/1.mp4");
                        add("/storage/emulated/0/2.mp4");
                    }
                });


            }

        }

        @Override
        protected WebSocket openWebSocket(IHTTPSession handshake) {
            return webSocket = new WebSocket(handshake) {
                @Override
                protected void onOpen() {
                    Log.i(TAG, "open");
                }

                @Override
                protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
                    Log.i(TAG, "onClose");
                }

                @Override
                protected void onMessage(WebSocketFrame message) {
                    Log.i(TAG, "onMessage " + message.toString());
                    try {
                        webSocket.send("我是服务端");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                protected void onPong(WebSocketFrame pong) {
                    Log.i(TAG, "onPong");
                }

                @Override
                protected void onException(IOException exception) {
                    Log.i(TAG, "onException");
                }
            };
        }
    }


}
