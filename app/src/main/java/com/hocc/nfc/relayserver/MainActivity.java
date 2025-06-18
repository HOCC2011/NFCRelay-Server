package com.hocc.nfc.relayserver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {
    private NfcAdapter nfcAdapter;
    private IsoDep publicIsoDep = null;
    private ServerSocket serverSocket;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private boolean isRunning = true;
    TextView help;
    TextView log;
    StringBuilder logString;
    Button clear;


    @SuppressLint("MissingInflatedId")
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
        setContentView(R.layout.activity_main);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        logString = new StringBuilder("");
        log = findViewById(R.id.Log);
        logString.append("NFC log:");
        log.setText(logString);
        help = findViewById(R.id.help);
        help.setText("IP address: " + getWifiIpAddress(getApplicationContext()) + "\nPort: 8888");
        clear = findViewById(R.id.clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logString = new StringBuilder("");
                logString.append("NFC log:");
                log.setText(logString);
            }
        });
        new ServerThread().start();
    }

    private void onCardTap(Tag tag) {
        Log.d("Debug", "Card Tapped!");
        Toast.makeText(this, "Card Tapped!", Toast.LENGTH_LONG).show();
        IsoDep isoDep = IsoDep.get(tag);

        if (isoDep == null) {
            Log.d("Debug", "Card is not an isoDep card");
            return;
        }

        try {
            isoDep.close();
            isoDep.connect();
            publicIsoDep = isoDep;
        } catch (IOException e) {
            Log.e("Error", "Error reading card", e);
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        help.setText("IP address: " + getWifiIpAddress(getApplicationContext()) + "\nPort: 8888");
        // Enable Reader Mode when activity is resumed
        if (nfcAdapter != null) {
            Bundle options = new Bundle();
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);

            nfcAdapter.enableReaderMode(
                    this,
                    new NfcAdapter.ReaderCallback() {
                        @Override
                        public void onTagDiscovered(Tag tag) {
                            // This callback will be triggered when a tag is detected
                            runOnUiThread(() -> onCardTap(tag));
                        }
                    },
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    options
            );
        }
        // Restart server if not running
        if (!isRunning) {
            isRunning = true;
            new ServerThread().start();
            Log.d("Server", "Server restarted in onResume()");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeSocket();
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeSocket();
    }

    private void closeSocket() {
        isRunning = false;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            Log.e("Server", "Error closing sockets", e);
        }
    }

    class ServerThread extends Thread {
        private final int PORT = 8888;
        private ServerSocket serverSocket;
        public volatile boolean isRunning = true;

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d("Server", "Server started, waiting for clients...");

                while (isRunning) {
                    Socket socket = serverSocket.accept(); // Accept new client
                    Log.d("Server", "Client connected");
                    new ClientHandler(socket).start(); // Handle in new thread
                }
            } catch (IOException e) {
                Log.e("Server", "Server error", e);
            }
        }

        public void stopServer() {
            isRunning = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException e) {
                Log.e("Server", "Error closing server", e);
            }
        }

        class ClientHandler extends Thread {
            private final Socket socket;

            ClientHandler(Socket socket) {
                this.socket = socket;
            }

            @Override
            public void run() {
                try (
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
                ) {
                    String CommandApdu;
                    while ((CommandApdu = in.readLine()) != null) {
                        final String cmd = CommandApdu;

                        runOnUiThread(() -> {
                            logString.append("\n\nReader:\n").append(cmd);
                            log.setText(logString.toString());
                        });

                        if (publicIsoDep == null) {
                            out.println("6D00"); // No card connected
                            continue;
                        }

                        boolean connected;
                        try {
                            connected = publicIsoDep.isConnected();
                        } catch (SecurityException e) {
                            // Tag expired or permission denied
                            connected = false;
                        }

                        if (!connected) {
                            out.println("6A82"); // Card disconnected or not found
                            continue;
                        }

                        try {
                            byte[] response = publicIsoDep.transceive(hexStringToByteArray(cmd));
                            final String hexResponse = bytesToHex(response);

                            runOnUiThread(() -> {
                                logString.append("\n\nCard response:\n").append(hexResponse);
                                log.setText(logString.toString());
                            });

                            out.println(hexResponse);
                        } catch (IOException e) {
                            out.println("6F00"); // Transceive error
                        }
                    }
                } catch (IOException e) {
                    Log.e("Server", "Client connection error", e);
                    runOnUiThread(() -> {
                        logString.append("\n\nConnection error: ").append(e);
                        log.setText(logString.toString());
                    });
                } finally {
                    try {
                        socket.close();
                        Log.d("Server", "Client disconnected");
                    } catch (IOException e) {
                        Log.e("Server", "Socket close error", e);
                    }
                }
            }
        }
    }


    public String getWifiIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            int ip = wifiManager.getConnectionInfo().getIpAddress();
            return String.format(
                    "%d.%d.%d.%d",
                    (ip & 0xff),
                    (ip >> 8 & 0xff),
                    (ip >> 16 & 0xff),
                    (ip >> 24 & 0xff)
            );
        }
        return "IP not found";
    }

    // Byte conversions
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        if (len % 2 != 0) {
            // Pad with leading 0 if odd length
            s = "0" + s;
            len = s.length();
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString().trim();
    }
}