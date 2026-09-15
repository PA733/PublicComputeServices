package com.kieronquinn.app.pcs.callscreen;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Developer utility: run explicitly to regenerate the bundled, prerecorded Chinese prompts. */
public final class ChineseVoiceRecorder extends Instrumentation {
    private TextToSpeech tts;
    private String requestedAction;
    private String requestedText;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            requestedAction = arguments.getString("action");
            requestedText = arguments.getString("text");
        }
        start();
    }
    @Override public void onStart() {
        Bundle status = new Bundle();
        try {
            CountDownLatch initialized = new CountDownLatch(1);
            AtomicInteger initialization = new AtomicInteger(-1);
            runOnMainSync(() -> tts = new TextToSpeech(getTargetContext(), result -> {
                initialization.set(result); initialized.countDown();
            }, "com.google.android.tts"));
            if (!initialized.await(30, TimeUnit.SECONDS) || initialization.get() != 0)
                throw new IllegalStateException("Google TTS did not initialize");
            Voice voice = tts.getVoices().stream()
                .filter(v -> v.getName().equals("zh-CN-language") && !v.isNetworkConnectionRequired())
                .findFirst().orElseThrow(() -> new IllegalStateException("Chinese voice is not installed"));
            if (tts.setVoice(voice) != 0) throw new IllegalStateException("Chinese voice unavailable");
            tts.setSpeechRate(1.0f); tts.setPitch(1.0f);
            String json;
            try (java.io.InputStream in = getTargetContext().getAssets().open("callscreen/zh-CN/phrases.json")) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JSONArray phrases = new JSONArray(json);
            File output = new File(getTargetContext().getFilesDir(), "recorded-chinese-prompts");
            if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Cannot create output");
            if (requestedText != null && requestedAction == null)
                throw new IllegalArgumentException("A text override requires one action");
            int count = 0;
            for (int i=0; i<phrases.length(); i++) {
                JSONObject phrase = phrases.getJSONObject(i);
                String action = phrase.getString("action");
                if (requestedAction != null && !requestedAction.equals(action)) continue;
                File wav = new File(output, action + ".wav");
                CountDownLatch completed = new CountDownLatch(1);
                AtomicInteger error = new AtomicInteger(0);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) {}
                    public void onDone(String id) { completed.countDown(); }
                    public void onError(String id) { error.set(-1); completed.countDown(); }
                    public void onError(String id, int code) { error.set(code); completed.countDown(); }
                });
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(wav,
                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY)) {
                    // The File overload was observed passing empty text on the test device.
                    String text = requestedText != null ? requestedText : phrase.getString("chinese");
                    int result = tts.synthesizeToFile(text, new Bundle(), fd, action);
                    if (result != 0 || !completed.await(30, TimeUnit.SECONDS) || error.get() != 0 || wav.length() <= 44)
                        throw new IllegalStateException("Failed recording " + action + ": " + error.get());
                }
                Bundle progress = new Bundle(); progress.putString("recorded", action); sendStatus(1, progress);
                count++;
            }
            if (count == 0) throw new IllegalArgumentException("No matching action: " + requestedAction);
            status.putInt("count", count); status.putString("directory", output.getAbsolutePath());
            finish(0, status);
        } catch (Throwable error) {
            status.putString("error", android.util.Log.getStackTraceString(error)); finish(1, status);
        } finally { if (tts != null) tts.shutdown(); }
    }
}
