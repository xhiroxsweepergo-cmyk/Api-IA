package com.apiia.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Api-IA: chat mínimo con Claude.
 *
 * La API key la escribe el usuario dentro de la app (menú ⋮ > Ajustes) y se guarda
 * solo en el teléfono. Nunca va dentro del APK ni del repositorio.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "api_ia";
    private static final String PREF_KEY = "api_key";
    private static final String PREF_MODEL = "model";
    private static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> history = new ArrayList<>(); // cada item: {rol, texto}

    private SharedPreferences prefs;
    private ScrollView scroll;
    private LinearLayout chat;
    private EditText input;
    private Button sendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        buildUi();
        addBubble(false, "Hola, soy Api-IA. Escríbeme lo que quieras.");
        if (prefs.getString(PREF_KEY, "").trim().isEmpty()) {
            showSettings();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- interfaz

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        scroll = new ScrollView(this);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(chat, new FrameLayout.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));

        input = new EditText(this);
        input.setHint("Escribe tu mensaje");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        bar.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1f));

        sendButton = new Button(this);
        sendButton.setText("Enviar");
        sendButton.setOnClickListener(v -> sendMessage());
        bar.addView(sendButton, new LinearLayout.LayoutParams(WRAP, WRAP));

        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        setContentView(root);
    }

    private TextView addBubble(boolean mine, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTextColor(Color.BLACK);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor(mine ? "#DCEBFF" : "#EEEEEE"));
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.setMargins(mine ? dp(48) : 0, dp(4), mine ? 0 : dp(48), dp(4));
        chat.addView(tv, lp);
        scrollToBottom();
        return tv;
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ----------------------------------------------------------------- ajustes

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Ajustes");
        menu.add(0, 2, 1, "Nueva conversación");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showSettings();
            return true;
        }
        if (item.getItemId() == 2) {
            if (!sendButton.isEnabled()) {
                Toast.makeText(this, "Espera a que termine la respuesta", Toast.LENGTH_SHORT).show();
                return true;
            }
            history.clear();
            chat.removeAllViews();
            addBubble(false, "Conversación nueva. ¿En qué te ayudo?");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText keyField = new EditText(this);
        keyField.setHint("API key (sk-ant-...)");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setText(prefs.getString(PREF_KEY, ""));
        box.addView(keyField, new LinearLayout.LayoutParams(MATCH, WRAP));

        final EditText modelField = new EditText(this);
        modelField.setHint("Modelo");
        modelField.setInputType(InputType.TYPE_CLASS_TEXT);
        modelField.setText(prefs.getString(PREF_MODEL, DEFAULT_MODEL));
        box.addView(modelField, new LinearLayout.LayoutParams(MATCH, WRAP));

        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setMessage("La clave se guarda solo en este teléfono. "
                        + "Créala en console.anthropic.com (se cobra por uso).")
                .setView(box)
                .setPositiveButton("Guardar", (dialog, which) -> prefs.edit()
                        .putString(PREF_KEY, keyField.getText().toString().trim())
                        .putString(PREF_MODEL, modelField.getText().toString().trim())
                        .apply())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ enviar

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        final String apiKey = prefs.getString(PREF_KEY, "").trim();
        if (apiKey.isEmpty()) {
            showSettings();
            return;
        }
        String savedModel = prefs.getString(PREF_MODEL, DEFAULT_MODEL).trim();
        final String model = savedModel.isEmpty() ? DEFAULT_MODEL : savedModel;

        input.setText("");
        addBubble(true, text);
        history.add(new String[] {"user", text});
        final TextView pending = addBubble(false, "…");
        sendButton.setEnabled(false);

        final List<String[]> snapshot = new ArrayList<>(history);
        executor.execute(() -> {
            String answer;
            boolean ok;
            try {
                answer = callClaude(apiKey, model, snapshot);
                ok = true;
            } catch (IOException e) {
                answer = "⚠️ No se pudo conectar. Revisa tu internet e inténtalo de nuevo.";
                ok = false;
            } catch (Exception e) {
                answer = "⚠️ " + e.getMessage();
                ok = false;
            }
            final String shown = answer;
            final boolean success = ok;
            ui.post(() -> {
                pending.setText(shown);
                if (success) {
                    history.add(new String[] {"assistant", shown});
                } else {
                    history.remove(history.size() - 1); // quita el mensaje que falló
                }
                sendButton.setEnabled(true);
                scrollToBottom();
            });
        });
    }

    private String callClaude(String apiKey, String model, List<String[]> messages) throws Exception {
        JSONArray jsonMessages = new JSONArray();
        for (String[] m : messages) {
            JSONObject o = new JSONObject();
            o.put("role", m[0]);
            o.put("content", m[1]);
            jsonMessages.put(o);
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 2048);
        body.put("messages", jsonMessages);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            boolean good = code >= 200 && code < 300;
            String response = readAll(good ? conn.getInputStream() : conn.getErrorStream());
            if (!good) {
                throw new Exception(errorText(code, response));
            }

            JSONArray content = new JSONObject(response).getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) {
                    sb.append(block.optString("text"));
                }
            }
            return sb.toString().trim();
        } finally {
            conn.disconnect();
        }
    }

    private static String errorText(int code, String body) {
        if (code == 401) {
            return "API key no válida. Revísala en el menú ⋮ > Ajustes.";
        }
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null) {
                return "Error " + code + ": " + error.optString("message", body);
            }
        } catch (Exception ignored) {
            // la respuesta no era JSON; se muestra tal cual
        }
        return "Error " + code + (body.isEmpty() ? "" : ": " + body);
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
}
