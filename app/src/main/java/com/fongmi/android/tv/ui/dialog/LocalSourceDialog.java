package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.LocalSource;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Prefers;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonObject;

import java.util.ArrayList;

public class LocalSourceDialog {

    public static void show(Context context, Runnable callback) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        CheckBox enable = new CheckBox(context);
        enable.setText(R.string.local_source_enable);
        enable.setChecked(Prefers.getBoolean("local_source_enabled", false));
        root.addView(enable);

        MaterialTextView dirsText = new MaterialTextView(context);
        dirsText.setTextSize(13);
        root.addView(dirsText);

        CheckBox auto = new CheckBox(context);
        auto.setText(R.string.local_source_auto);
        auto.setChecked(Prefers.getBoolean("local_source_auto", true));
        root.addView(auto);

        MaterialButton settings = new MaterialButton(context);
        settings.setText(R.string.local_source_settings_title);
        settings.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(settings);

        MaterialTextView summaryText = new MaterialTextView(context);
        summaryText.setTextSize(13);
        root.addView(summaryText);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            ArrayList<String> dirs = LocalSource.stringList(LocalSource.loadSettings(), "scan_dirs");
            ArrayList<String> enabled = LocalSource.stringList(LocalSource.loadSettings(), "scan_enabled_dirs");
            StringBuilder sb = new StringBuilder(context.getString(R.string.local_source_settings_dirs));
            if (dirs.isEmpty()) {
                sb.append("\n").append(context.getString(R.string.local_source_settings_dirs_empty));
            } else {
                for (String dir : dirs) sb.append("\n").append(enabled.contains(dir) ? "☑ " : "☐ ").append(dir);
            }
            dirsText.setText(sb.toString());
            StringBuilder sb2 = new StringBuilder(context.getString(R.string.local_source_status, LocalSource.summary()));
            String file = Prefers.getString("local_source_file");
            if (TextUtils.isEmpty(file)) file = LocalSource.outputPath(LocalSource.loadSettings());
            if (!TextUtils.isEmpty(file)) sb2.append("\n").append(context.getString(R.string.local_source_output, file));
            summaryText.setText(sb2.toString());
        };
        refresh[0].run();
        enable.setOnCheckedChangeListener((button, checked) -> refresh[0].run());
        settings.setOnClickListener(view -> LocalSourceSettingsDialog.show(context, refresh[0]));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);

        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_title), scroll,
                context.getString(R.string.local_source_settings_save_load), view -> {
                    Prefers.put("local_source_enabled", enable.isChecked());
                    Prefers.put("local_source_auto", auto.isChecked());
                    if (LocalSource.stringList(LocalSource.loadSettings(), "scan_dirs").isEmpty()) {
                        Notify.show(context.getString(R.string.local_source_dir_required));
                        return;
                    }
                    holder[0].dismiss();
                    scanAndLoad(context, callback);
                },
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].show();
    }

    public static void scanAndLoad(Context context, Runnable callback) {
        Notify.show(context.getString(R.string.local_source_scanning));
        new Thread(() -> {
            JsonObject generated = LocalSource.buildCached(true);
            String path = LocalSource.writeGenerated(generated);
            if (path != null) Prefers.put("local_source_file", path);
            String summary = Prefers.getString("local_source_summary");
            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.run();
                Notify.show(context.getString(R.string.local_source_done, summary));
                try {
                    VodConfig.get().clear().config(VodConfig.get().getConfig()).load(new Callback());
                } catch (Throwable ignored) {
                }
            });
        }, "local-source-scan").start();
    }
}
