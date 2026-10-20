package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.LocalSource;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Prefers;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

public class LocalSourceSettingsDialog {

    private final Context context;
    private final Runnable onClose;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout dirsBox;
    private LinearLayout blockedBox;
    private LinearLayout excludedBox;
    private LinearLayout extBox;
    private MaterialTextView candidatesText;
    private MaterialTextView extHeader;
    private MaterialTextView spiderText;
    private MaterialButton parsesButton;
    private EditText outputInput;
    private EditText liveInput;
    private RadioGroup modeGroup;
    private RadioButton modeFile;
    private RadioButton modeSource;
    private CheckBox replaceBox;
    private CheckBox mergeBox;
    private CheckBox base64Box;

    private final ArrayList<String> dirNames = new ArrayList<>();
    private final ArrayList<String> fileNames = new ArrayList<>();
    private final ArrayList<String> extNames = new ArrayList<>();
    private String spiderDisplay = "";
    private int excludedShown = 120;

    private LocalSourceSettingsDialog(Context context, Runnable onClose) {
        this.context = context;
        this.onClose = onClose;
    }

    public static void show(Context context, Runnable onClose) {
        new LocalSourceSettingsDialog(context, onClose).build();
    }

    private MaterialTextView label(int resId) {
        MaterialTextView view = new MaterialTextView(context);
        view.setText(resId);
        view.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = 16;
        view.setLayoutParams(params);
        return view;
    }

    private MaterialTextView note(String text) {
        MaterialTextView view = new MaterialTextView(context);
        view.setText(text);
        view.setTextSize(12);
        return view;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setChecked(checked);
        box.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void build() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(4, 0, 4, 0);

        root.addView(label(R.string.local_source_settings_dirs));
        dirsBox = new LinearLayout(context);
        dirsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(dirsBox);
        LinearLayout addRow = new LinearLayout(context);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText dirInput = new EditText(context);
        dirInput.setHint(R.string.local_source_dir_hint);
        dirInput.setSingleLine(true);
        dirInput.setTextSize(13);
        dirInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton addButton = new MaterialButton(context);
        addButton.setText(R.string.local_source_settings_dir_add);
        addRow.addView(dirInput);
        addRow.addView(addButton);
        root.addView(addRow);
        addButton.setOnClickListener(view -> {
            String trim = dirInput.getText() == null ? "" : dirInput.getText().toString().trim();
            if (TextUtils.isEmpty(trim)) {
                Notify.show(context.getString(R.string.local_source_dir_required));
                return;
            }
            JsonObject cfg = LocalSource.loadSettings();
            ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
            if (!dirs.contains(trim)) dirs.add(trim);
            ArrayList<String> enabled = LocalSource.stringList(cfg, "scan_enabled_dirs");
            if (!enabled.contains(trim)) enabled.add(trim);
            cfg.add("scan_dirs", LocalSource.toJsonArray(dirs));
            cfg.add("scan_enabled_dirs", LocalSource.toJsonArray(enabled));
            LocalSource.saveSettings(cfg);
            dirInput.setText("");
            fillDirs();
            refreshCandidates();
        });

        candidatesText = new MaterialTextView(context);
        candidatesText.setTextSize(12);
        root.addView(candidatesText);
        MaterialButton refreshButton = new MaterialButton(context);
        refreshButton.setText(R.string.local_source_settings_list_refresh);
        refreshButton.setOnClickListener(view -> refreshCandidates());
        root.addView(refreshButton);

        root.addView(label(R.string.local_source_settings_blocked));
        blockedBox = new LinearLayout(context);
        blockedBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(blockedBox);

        root.addView(label(R.string.local_source_settings_excluded));
        excludedBox = new LinearLayout(context);
        excludedBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(excludedBox);

        root.addView(label(R.string.local_source_settings_extensions));
        extHeader = new MaterialTextView(context);
        extHeader.setTextSize(13);
        root.addView(extHeader);
        extBox = new LinearLayout(context);
        extBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(extBox);
        extHeader.setOnClickListener(view -> {
            ArrayList<String> all = extNames.isEmpty() ? new ArrayList<>(Arrays.asList(LocalSource.EXTENSIONS)) : new ArrayList<>(extNames);
            LocalSource.saveExtensions(all);
            fillExtensions();
        });

        root.addView(label(R.string.local_source_settings_mode));
        modeGroup = new RadioGroup(context);
        modeGroup.setOrientation(LinearLayout.HORIZONTAL);
        modeFile = new RadioButton(context);
        modeFile.setText(R.string.local_source_settings_mode_file);
        modeSource = new RadioButton(context);
        modeSource.setText(R.string.local_source_settings_mode_source);
        modeGroup.addView(modeFile);
        modeGroup.addView(modeSource);
        root.addView(modeGroup);
        root.addView(note(context.getString(R.string.local_source_settings_mode_hint)));

        root.addView(label(R.string.local_source_settings_output));
        outputInput = new EditText(context);
        outputInput.setSingleLine(true);
        outputInput.setTextSize(13);
        root.addView(outputInput);

        JsonObject cfg = LocalSource.loadSettings();
        replaceBox = check(context.getString(R.string.local_source_settings_replace), LocalSource.getBool(cfg, "replace_sites", true));
        replaceBox.setVisibility(View.GONE);
        root.addView(replaceBox);
        mergeBox = check(context.getString(R.string.local_source_settings_merge), LocalSource.getBool(cfg, "merge_same_name", false));
        mergeBox.setOnCheckedChangeListener((button, checked) -> LocalSource.updateSetting(new JsonPrimitive(checked), "merge_same_name"));
        root.addView(mergeBox);
        base64Box = check(context.getString(R.string.local_source_settings_base64), LocalSource.getBool(cfg, "decode_base64", true));
        root.addView(base64Box);

        root.addView(label(R.string.local_source_settings_extra));
        parsesButton = new MaterialButton(context);
        parsesButton.setOnClickListener(view -> editParses());
        root.addView(parsesButton);

        root.addView(label(R.string.local_source_settings_spider));
        spiderText = new MaterialTextView(context);
        spiderText.setTextSize(13);
        root.addView(spiderText);
        LinearLayout spiderRow = new LinearLayout(context);
        spiderRow.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton pickButton = new MaterialButton(context);
        pickButton.setText(R.string.local_source_settings_spider_pick);
        pickButton.setOnClickListener(view -> pickJar());
        MaterialButton clearSpider = new MaterialButton(context);
        clearSpider.setText(R.string.local_source_settings_clear);
        clearSpider.setOnClickListener(view -> setSpider(""));
        spiderRow.addView(pickButton);
        spiderRow.addView(clearSpider);
        root.addView(spiderRow);

        root.addView(label(R.string.local_source_settings_live_url));
        LinearLayout liveRow = new LinearLayout(context);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        liveInput = new EditText(context);
        liveInput.setSingleLine(true);
        liveInput.setTextSize(13);
        liveInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton clearLive = new MaterialButton(context);
        clearLive.setText(R.string.local_source_settings_clear);
        clearLive.setOnClickListener(view -> liveInput.setText(""));
        liveRow.addView(liveInput);
        liveRow.addView(clearLive);
        root.addView(liveRow);

        root.addView(note(context.getString(R.string.local_source_settings_hint)));

        initValues(cfg);
        fillDirs();
        fillExtensions();
        refreshCandidates();

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);

        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_settings_title), scroll,
                context.getString(R.string.local_source_settings_save_load), view -> {
                    JsonObject current = LocalSource.loadSettings();
                    LocalSource.updateSetting(new JsonPrimitive(outputInput.getText() == null ? "" : outputInput.getText().toString().trim()), "output_path");
                    LocalSource.updateSetting(new JsonPrimitive(modeFile.isChecked() ? "file" : "source"), "scan_mode");
                    LocalSource.updateSetting(new JsonPrimitive(replaceBox.isChecked()), "replace_sites");
                    LocalSource.updateSetting(new JsonPrimitive(mergeBox.isChecked()), "merge_same_name");
                    if (!TextUtils.isEmpty(spiderDisplay)) LocalSource.updateSetting(new JsonPrimitive(spiderDisplay), "spider_jar");
                    LocalSource.updateSetting(new JsonPrimitive(liveInput.getText() == null ? "" : liveInput.getText().toString().trim()), "live_url");
                    LocalSource.updateSetting(new JsonPrimitive(base64Box.isChecked()), "decode_base64");
                    if (LocalSource.stringList(current, "scan_dirs").isEmpty()) {
                        Notify.show(context.getString(R.string.local_source_dir_required));
                        return;
                    }
                    holder[0].dismiss();
                    LocalSourceDialog.scanAndLoad(context, onClose);
                },
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].setOnDismissListener(dialog -> {
            if (onClose != null) onClose.run();
        });
        holder[0].show();
    }

    private void initValues(JsonObject cfg) {
        outputInput.setText(LocalSource.getString(cfg, "output_path"));
        modeGroup.check("file".equals(LocalSource.getString(cfg, "scan_mode")) ? modeFile.getId() : modeSource.getId());
        setSpider(LocalSource.spiderJarPath());
        liveInput.setText(LocalSource.liveUrl());
        refreshParsesButton(cfg);
    }

    private void refreshParsesButton(JsonObject cfg) {
        JsonArray entries = cfg.has("parse_entries") && cfg.get("parse_entries").isJsonArray() ? cfg.getAsJsonArray("parse_entries") : LocalSource.defaultParses();
        parsesButton.setText(context.getString(R.string.local_source_settings_parses, entries.size()));
    }

    private void setSpider(String path) {
        spiderDisplay = path == null ? "" : path.trim();
        spiderText.setText(TextUtils.isEmpty(spiderDisplay) ? context.getString(R.string.local_source_settings_spider_none) : spiderDisplay);
    }

    private void fillDirs() {
        dirsBox.removeAllViews();
        JsonObject cfg = LocalSource.loadSettings();
        ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
        ArrayList<String> enabled = LocalSource.stringList(cfg, "scan_enabled_dirs");
        if (dirs.isEmpty()) {
            dirsBox.addView(note(context.getString(R.string.local_source_settings_dirs_empty)));
            return;
        }
        for (String dir : dirs) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            CheckBox box = check(dir, enabled.contains(dir));
            box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.setOnCheckedChangeListener((button, checked) -> {
                JsonObject current = LocalSource.loadSettings();
                ArrayList<String> all = LocalSource.stringList(current, "scan_dirs");
                ArrayList<String> en = LocalSource.stringList(current, "scan_enabled_dirs");
                if (checked) {
                    if (!en.contains(dir)) en.add(dir);
                } else {
                    en.remove(dir);
                }
                current.add("scan_enabled_dirs", LocalSource.toJsonArray(LocalSource.intersect(all, en)));
                LocalSource.saveSettings(current);
            });
            MaterialButton remove = new MaterialButton(context);
            remove.setText(R.string.local_source_settings_dir_remove);
            remove.setOnClickListener(view -> {
                JsonObject current = LocalSource.loadSettings();
                ArrayList<String> all = LocalSource.stringList(current, "scan_dirs");
                ArrayList<String> en = LocalSource.stringList(current, "scan_enabled_dirs");
                all.remove(dir);
                en.remove(dir);
                current.add("scan_dirs", LocalSource.toJsonArray(all));
                current.add("scan_enabled_dirs", LocalSource.toJsonArray(en));
                LocalSource.saveSettings(current);
                fillDirs();
                refreshCandidates();
            });
            row.addView(box);
            row.addView(remove);
            dirsBox.addView(row);
        }
    }

    private void fillBlocked() {
        blockedBox.removeAllViews();
        if (dirNames.isEmpty()) {
            blockedBox.addView(note(context.getString(R.string.local_source_settings_list_empty)));
            return;
        }
        ArrayList<String> blocked = LocalSource.stringList(LocalSource.loadSettings(), "blocked_dirs");
        for (String name : dirNames) {
            CheckBox box = check(name, blocked.contains(name));
            box.setOnCheckedChangeListener((button, checked) -> toggleListSetting("blocked_dirs", name, checked));
            blockedBox.addView(box);
        }
    }

    private void fillExcluded() {
        excludedBox.removeAllViews();
        if (fileNames.isEmpty()) {
            excludedBox.addView(note(context.getString(R.string.local_source_settings_list_empty)));
            return;
        }
        ArrayList<String> excluded = LocalSource.stringList(LocalSource.loadSettings(), "excluded_files");
        int min = Math.min(excludedShown, fileNames.size());
        for (int i = 0; i < min; i++) {
            String name = fileNames.get(i);
            CheckBox box = check(name, excluded.contains(name));
            box.setOnCheckedChangeListener((button, checked) -> toggleListSetting("excluded_files", name, checked));
            excludedBox.addView(box);
        }
        int left = fileNames.size() - min;
        if (left > 0) {
            MaterialButton more = new MaterialButton(context);
            more.setText(context.getString(R.string.local_source_settings_list_more, left));
            more.setOnClickListener(view -> {
                excludedShown += 120;
                fillExcluded();
            });
            excludedBox.addView(more);
        }
    }

    private void toggleListSetting(String key, String name, boolean add) {
        ArrayList<String> list = new ArrayList<>(LocalSource.splitUserText(TextUtils.join(",", LocalSource.stringList(LocalSource.loadSettings(), key))));
        if (add) {
            if (!list.contains(name)) list.add(name);
        } else {
            list.remove(name);
        }
        LocalSource.updateSetting(LocalSource.toJsonArray(new ArrayList<>(LocalSource.splitUserText(TextUtils.join(",", list)))), key);
    }

    private void fillExtensions() {
        extBox.removeAllViews();
        ArrayList<String> list = extNames.isEmpty() ? new ArrayList<>(Arrays.asList(LocalSource.EXTENSIONS)) : extNames;
        ArrayList<String> selected = LocalSource.stringList(LocalSource.loadSettings(), "scan_extensions");
        boolean empty = selected.isEmpty();
        for (String ext : list) {
            CheckBox box = check(ext, empty || selected.contains(ext));
            box.setOnCheckedChangeListener((button, checked) -> {
                ArrayList<String> current = new ArrayList<>(LocalSource.stringList(LocalSource.loadSettings(), "scan_extensions"));
                if (current.isEmpty()) current.addAll(list);
                if (checked) {
                    if (!current.contains(ext)) current.add(ext);
                } else {
                    current.remove(ext);
                }
                LocalSource.saveExtensions(current);
                fillExtensions();
            });
            extBox.addView(box);
        }
        extHeader.setText(context.getString(R.string.local_source_settings_ext_all, empty ? list.size() : selected.size(), list.size()));
    }

    private void refreshCandidates() {
        candidatesText.setText(R.string.local_source_settings_list_loading);
        blockedBox.removeAllViews();
        excludedBox.removeAllViews();
        new Thread(() -> {
            ArrayList<String> dirs = LocalSource.collectNames(true);
            ArrayList<String> files = LocalSource.collectNames(false);
            JsonObject cfg = LocalSource.loadSettings();
            LinkedHashSet<String> found = new LinkedHashSet<>();
            for (String dir : LocalSource.enabledDirs(cfg)) {
                File file = new File(dir);
                if (file.isDirectory()) LocalSource.collectExtensions(file, 0, found, cfg);
            }
            ArrayList<String> exts = new ArrayList<>();
            for (String ext : LocalSource.EXTENSIONS) if (found.contains(ext)) exts.add(ext);
            if (exts.isEmpty()) exts.addAll(Arrays.asList(LocalSource.EXTENSIONS));
            ArrayList<String> finalExts = exts;
            main.post(() -> {
                dirNames.clear();
                dirNames.addAll(dirs);
                fileNames.clear();
                fileNames.addAll(files);
                extNames.clear();
                extNames.addAll(finalExts);
                candidatesText.setText(context.getString(R.string.local_source_settings_list_count, extNames.size(), dirNames.size(), fileNames.size()));
                fillBlocked();
                fillExcluded();
                fillExtensions();
            });
        }, "local-source-candidates").start();
    }

    private void editParses() {
        JsonObject cfg = LocalSource.loadSettings();
        JsonArray entries = cfg.has("parse_entries") && cfg.get("parse_entries").isJsonArray() ? cfg.getAsJsonArray("parse_entries") : LocalSource.defaultParses();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 8, 24, 8);
        ArrayList<CheckBox> boxes = new ArrayList<>();
        ArrayList<EditText> inputs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).isJsonObject()) continue;
            JsonObject entry = entries.get(i).getAsJsonObject();
            CheckBox check = check(LocalSource.getString(entry, "name"), LocalSource.getBool(entry, "enabled", true));
            EditText input = new EditText(context);
            input.setText(LocalSource.getString(entry, "url"));
            input.setSingleLine(true);
            input.setTextSize(12);
            box.addView(check);
            box.addView(input);
            boxes.add(check);
            inputs.add(input);
        }
        if (boxes.isEmpty()) box.addView(note(context.getString(R.string.local_source_settings_parses_empty)));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_settings_parses, entries.size()), scroll,
                context.getString(R.string.dialog_positive), view -> {
                    JsonArray out = new JsonArray();
                    int index = 0;
                    for (int i = 0; i < entries.size(); i++) {
                        if (!entries.get(i).isJsonObject()) continue;
                        JsonObject copy = entries.get(i).getAsJsonObject().deepCopy();
                        copy.addProperty("enabled", boxes.get(index).isChecked());
                        String url = inputs.get(index).getText() == null ? "" : inputs.get(index).getText().toString().trim();
                        if (!TextUtils.isEmpty(url)) copy.addProperty("url", url);
                        out.add(copy);
                        index++;
                    }
                    LocalSource.updateSetting(out, "parse_entries");
                    refreshParsesButton(LocalSource.loadSettings());
                    holder[0].dismiss();
                },
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].show();
    }

    private void pickJar() {
        new Thread(() -> {
            JsonObject cfg = LocalSource.loadSettings();
            ArrayList<String> dirs = LocalSource.stringList(cfg, "scan_dirs");
            if (dirs.isEmpty()) dirs = LocalSource.enabledDirs(cfg);
            ArrayList<String> jars = new ArrayList<>();
            for (String dir : dirs) {
                File file = new File(dir);
                if (file.isDirectory()) LocalSource.collectJars(file, 0, LocalSource.maxDepth(cfg), jars);
            }
            jars.sort(LocalSource.BY_NAME_STR);
            if (jars.size() > 3000) jars = new ArrayList<>(jars.subList(0, 3000));
            ArrayList<String> result = jars;
            main.post(() -> showJarPicker(result));
        }, "local-source-jars").start();
    }

    private void showJarPicker(ArrayList<String> jars) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        if (jars.isEmpty()) box.addView(note(context.getString(R.string.local_source_settings_spider_empty)));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        Dialog[] holder = new Dialog[1];
        for (String jar : jars) {
            MaterialButton item = new MaterialButton(context);
            item.setText(jar);
            item.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(view -> {
                setSpider(jar);
                holder[0].dismiss();
            });
            box.addView(item);
        }
        holder[0] = LightDialog.create(context, context.getString(R.string.local_source_settings_spider_pick_title), scroll,
                context.getString(R.string.dialog_negative), view -> holder[0].dismiss(), null, null);
        holder[0].show();
    }
}
