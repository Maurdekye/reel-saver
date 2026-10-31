package io.github.maurdekye.reelsaver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One simple screen: share a reel to Reel Saver from Instagram, or paste a link here.
 */
public class MainActivity extends Activity {

    private EditText linkInput;
    private TextView statusText;
    private final StatusBus.Listener statusListener = message -> {
        if (statusText != null) statusText.setText(message);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNotificationPermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        StatusBus.register(statusListener);
    }

    @Override
    protected void onStop() {
        StatusBus.unregister(statusListener);
        super.onStop();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Save an Instagram reel");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView instructions = new TextView(this);
        instructions.setText(
                "In Instagram, open a reel, tap Share, and pick Reel Saver. "
                        + "The video is saved to Movies/Reel Saver in your gallery.\n\n"
                        + "Or paste a reel link here:");
        instructions.setPadding(0, dp(12), 0, dp(12));
        layout.addView(instructions);

        linkInput = new EditText(this);
        linkInput.setHint("https://www.instagram.com/reel/…");
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkInput.setMaxLines(3);
        layout.addView(linkInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button download = new Button(this);
        download.setText("Download reel");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(12);
        layout.addView(download, buttonParams);
        download.setOnClickListener(v -> {
            String text = linkInput.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "Paste a reel link first.", Toast.LENGTH_SHORT).show();
                return;
            }
            startDownload(text);
        });

        statusText = new TextView(this);
        statusText.setPadding(0, dp(20), 0, 0);
        layout.addView(statusText);

        TextView note = new TextView(this);
        note.setText("Reels from private accounts can't be saved without logging in, "
                + "and Reel Saver never asks for your Instagram password.");
        note.setPadding(0, dp(24), 0, 0);
        note.setTextSize(13);
        layout.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null) shared = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        if (shared == null) {
            statusText.setText("That share didn't include a link.");
            return;
        }
        String text = shared.toString();
        linkInput.setText(text);
        startDownload(text);
    }

    private void startDownload(String text) {
        statusText.setText("Starting…");
        DownloadService.start(this, text);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
