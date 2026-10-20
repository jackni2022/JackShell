package com.fongmi.android.tv.api;

import android.net.Uri;
import android.util.Base64;
import android.text.TextUtils;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Prefers;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class LocalSource {

    public static final String[] STORAGE_PREFIXES = {"/storage/emulated/0/", "/storage/emulated/legacy/", "/storage/self/primary/", "/mnt/user/0/primary/", "/sdcard/"};
    public static final String[] STORAGE_ROOTS = {"/storage/emulated/0", "/sdcard", "/storage/self/primary"};
    public static final String[] TAG_XBPQ = {"PQ类"};
    public static final String[] TAG_XYQ = {"YQ类"};
    public static final String[] TAG_DRPY = {"JS[Drpy]"};
    public static final String[] TAG_PKG = {"本地包", "package", "pkg", "包"};
    public static final String[] EXTENSIONS = {"py", "js", "php", "html", "json", "txt", "m3u", "db", "zip", "pkg"};
    public static final Pattern BRACKET = Pattern.compile("\\[([^\\]]+)]");
    public static final Object LOCK = new Object();

    public static JsonObject cache;
    public static long cacheTime;
    public static volatile int lastSiteCount;
    public static long lastWriteTime;
    public static volatile boolean refreshing;
    public static String lastMoved = "";
    public static String lastWritten = "";

    public static class NameInfo {
        public String base = "";
        public String ext = "";
        public String rawExt = "";
        public String kind = "";
    }

    public static class ScanCtx {
        public int a;
        public int b;
        public int c;
        public boolean d;
        public Object e = new ArrayList();
        public Object f = new ArrayList();
        public Object g = new LinkedHashSet();
        public Object h = new LinkedHashSet();
        public Object i = new LinkedHashSet();
        public Object j = new ArrayList();

        @SuppressWarnings("unchecked")
        public void note(String name, String reason) {
            this.c++;
            ArrayList<String> list = (ArrayList<String>) this.j;
            if (list.size() < 12) list.add(name + "：" + reason);
        }
    }

    public static final Comparator<File> BY_NAME = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return gbkCompare(a.getName(), b.getName());
        }
    };

    public static final Comparator<String> BY_NAME_STR = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return gbkCompare(a, b);
        }
    };

    static String traceOf(Throwable th) {
        try {
            StringWriter sw = new StringWriter();
            th.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String tagFlags(String name) {
        StringBuilder sb = new StringBuilder();
        if (containsAny(name, Arrays.asList(TAG_XBPQ))) sb.append("xbpq,");
        if (containsAny(name, Arrays.asList(TAG_XYQ))) sb.append("xyq,");
        if (containsAny(name, Arrays.asList(TAG_DRPY))) sb.append("drpy,");
        if (containsAny(name, Arrays.asList(TAG_PKG))) sb.append("pkg,");
        return sb.toString();
    }

    public static boolean isUnderDirs(ArrayList<String> dirs, String path) {
        if (TextUtils.isEmpty(path)) return false;
        Iterator<String> it = dirs.iterator();
        while (it.hasNext()) {
            String dir = it.next();
            if (!TextUtils.isEmpty(dir)) {
                if (path.equals(dir)) return true;
                if (!dir.endsWith("/")) dir = dir.concat("/");
                if (path.startsWith(dir)) return true;
            }
        }
        return false;
    }

    public static void updateSetting(JsonElement element, String key) {
        JsonObject settings = loadSettings();
        settings.add(key, element);
        saveSettings(settings);
        if (Prefers.getBoolean("local_source_enabled", false) && !refreshing) {
            refreshing = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        writeBack(buildCached(false), false);
                    } catch (Throwable th) {
                        DebugLogStore.add("LocalSource", "本地源自动回写（后台）失败：" + th);
                    } finally {
                        refreshing = false;
                    }
                }
            }, "local-source-autowrite").start();
        }
    }

    public static ArrayList<String> enabledDirs(JsonObject cfg) {
        ArrayList<String> dirs = stringList(cfg, "scan_dirs");
        ArrayList<String> enabled = stringList(cfg, "scan_enabled_dirs");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<String> out = new ArrayList<>();
        Iterator<String> it = dirs.iterator();
        while (it.hasNext()) {
            String dir = it.next();
            if (enabled.contains(dir)) {
                String canon = canonPath(dir);
                if (!TextUtils.isEmpty(canon) && seen.add(canon)) out.add(dir);
            }
        }
        return out;
    }

    public static String insertTag(String name, String tag) {
        int idx = name.lastIndexOf('(');
        if (idx <= 0) return name + "·" + tag;
        return name.substring(0, idx) + "·" + tag + name.substring(idx);
    }

    public static int sitePriority(JsonObject site) {
        String api = getString(site, "api");
        if ("csp_XBPQ".equals(api)) return 60;
        if ("csp_XYQHiker".equals(api)) return 50;
        if (api.endsWith(".js")) return 45;
        if (api.endsWith(".py")) return 40;
        return api.startsWith("csp_") ? 30 : 20;
    }

    public static String writeGenerated(JsonObject cfg) {
        try {
            String out = outputPath(loadSettings());
            if (TextUtils.isEmpty(out)) {
                DebugLogStore.add("LocalSource", "未设置扫描目录，无法确定生成位置");
                return null;
            }
            String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(cfg);
            if (json.length() > 8388608) {
                DebugLogStore.add("LocalSource", "生成内容过大（" + json.length() + " 字符），已放弃落盘");
                return null;
            }
            File file = new File(out);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("无法创建目录 " + parent);
            }
            FileOutputStream fos = new FileOutputStream(file, false);
            try {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                fos.close();
            }
            DebugLogStore.add("LocalSource", "已生成：" + file.getAbsolutePath() + "（" + json.length() + " 字符）");
            return file.getAbsolutePath();
        } catch (Throwable th) {
            DebugLogStore.add("LocalSource", "生成失败：" + th + traceOf(th));
            return null;
        }
    }

    public static NameInfo parseName(String filename) {
        NameInfo info = new NameInfo();
        Locale locale = Locale.US;
        if (filename.toLowerCase(locale).endsWith(".wv.js")) {
            info.rawExt = "wv.js";
            info.kind = "js";
            info.ext = "js";
            info.base = filename.substring(0, filename.length() - 6);
            return info;
        }
        int idx = filename.lastIndexOf('.');
        if (idx > 0) {
            info.base = filename.substring(0, idx);
            info.rawExt = filename.substring(idx + 1).toLowerCase(locale);
        } else {
            info.base = filename;
        }
        info.kind = info.rawExt;
        info.ext = info.rawExt;
        return info;
    }

    public static void writeText(File file, String text) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            try {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                fos.close();
            }
        } catch (Throwable th) {
            DebugLogStore.add("LocalSource", "写 json 失败：" + file.getAbsolutePath() + "：" + th);
        }
    }

    public static String resolveSiteFile(JsonObject site) {
        try {
            String tagged = getStringOrNull(site, "#path");
            if (!TextUtils.isEmpty(tagged)) {
                String normalized = normalizePath(tagged);
                if (!TextUtils.isEmpty(normalized)) return normalized;
            }
            String[] keys = {"ext", "homePage", "api"};
            for (int i = 0; i < 3; i++) {
                String value = getStringOrNull(site, keys[i]);
                if (!TextUtils.isEmpty(value)) {
                    Iterator<String> it = candidates(value).iterator();
                    while (it.hasNext()) {
                        String cand = it.next();
                        if (!TextUtils.isEmpty(cand)) {
                            if (cand.startsWith("/")) return normalizePath(cand);
                            String base = "";
                            try {
                                ArrayList<String> roots = candidateRoots(loadSettings());
                                Iterator<String> it2 = roots.iterator();
                                while (true) {
                                    if (it2.hasNext()) {
                                        String root = it2.next();
                                        if (new File(root, cand).isFile()) {
                                            base = root;
                                            break;
                                        }
                                    } else if (!roots.isEmpty()) {
                                        base = roots.get(0);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            if (!TextUtils.isEmpty(base)) return normalizePath(base + "/" + cand);
                        }
                    }
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static JsonObject fileSpiderSite(String key, String extPath) {
        JsonObject site = new JsonObject();
        site.addProperty("key", key);
        site.addProperty("name", key);
        site.addProperty("type", 3);
        site.addProperty("api", "csp_FileSpider");
        site.addProperty("jar", "./lib/WvSpider.jar");
        site.addProperty("searchable", 0);
        site.addProperty("quickSearch", 0);
        site.addProperty("filterable", 0);
        site.addProperty("switchable", 0);
        site.addProperty("changeable", 0);
        site.addProperty("ext", extPath);
        return site;
    }

    public static ArrayList<String> intersect(ArrayList<String> first, ArrayList<String> second) {
        ArrayList<String> out = new ArrayList<>();
        Iterator<String> it = first.iterator();
        while (it.hasNext()) {
            String item = it.next();
            if (second.contains(item)) out.add(item);
        }
        return out;
    }

    public static File findFile(File dir, String name, int depth, int maxDepth) {
        File[] files;
        if (depth > maxDepth || !dir.isDirectory() || (files = dir.listFiles()) == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(name)) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFile(f, name, depth + 1, maxDepth);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static File findUniqueFile(String name) {
        ArrayList<File> found = new ArrayList<>();
        Iterator<String> it = candidateRoots(loadSettings()).iterator();
        while (it.hasNext()) {
            File dir = new File(it.next());
            if (dir.isDirectory()) {
                findByName(dir, name.toLowerCase(Locale.US), found, 0);
                if (found.size() > 1) return null;
            }
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    public static String dedupKey(JsonObject obj, String keyField) {
        String api = getString(obj, "api");
        if (TextUtils.isEmpty(api)) api = getString(obj, "url");
        StringBuilder sb = new StringBuilder();
        sb.append(getString(obj, keyField));
        sb.append("\u0000");
        String normalized = "";
        String trim = api == null ? "" : api.trim();
        if (!TextUtils.isEmpty(trim)) {
            try {
                ArrayList<String> cands = candidates(trim);
                if (!cands.isEmpty()) normalized = stripRootPrefix(cands.get(0));
            } catch (Throwable ignored) {
            }
            normalized = trim;
        }
        sb.append(normalized);
        return sb.toString();
    }

    public static String displayName(String raw, String bracket, String tag) {
        String cleaned = stripBrackets(raw == null ? "" : raw.replaceAll("\\[[^\\]]*\\]", ""));
        if (TextUtils.isEmpty(cleaned)) cleaned = stripBrackets(raw);
        if (TextUtils.isEmpty(cleaned)) cleaned = "未命名";
        String middle = TextUtils.isEmpty(bracket) ? "" : "[" + stripBrackets(bracket) + "]";
        StringBuilder sb = new StringBuilder();
        sb.append(cleaned);
        sb.append(middle);
        sb.append("(");
        sb.append(tag == null ? "" : tag);
        sb.append(")");
        return sb.toString();
    }

    public static String liveUrl() {
        String found = "";
        JsonObject cfg = loadSettings();
        if (!cfg.has("live_url")) return "";
        String configured = getString(cfg, "live_url");
        try {
            JsonElement defaults = defaults().get("DEFAULT_LIVES");
            if (defaults != null && defaults.isJsonArray()) {
                Iterator<JsonElement> it = defaults.getAsJsonArray().iterator();
                while (it.hasNext()) {
                    JsonElement item = it.next();
                    if (item.isJsonObject()) {
                        found = getString(item.getAsJsonObject(), "url");
                        if (!TextUtils.isEmpty(found)) break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return configured.equals(found) ? "" : configured;
    }

    public static String spiderJarPath() {
        JsonObject cfg = loadSettings();
        if (cfg.has("spider_jar")) return getString(cfg, "spider_jar");
        String configured = defaultString("CONFIG_SPIDER");
        if (TextUtils.isEmpty(configured)) return "";
        String outDir = outputDir(cfg);
        if (!TextUtils.isEmpty(outDir)) {
            File file = new File(outDir, configured.startsWith("./") ? configured.substring(2) : configured);
            if (file.isFile()) return file.getAbsolutePath();
        }
        String name = new File(configured).getName();
        if (TextUtils.isEmpty(name)) return "";
        ArrayList<String> dirs = enabledDirs(cfg);
        int depth = maxDepth(cfg);
        File found = null;
        Iterator<String> it = dirs.iterator();
        while (it.hasNext()) {
            found = findFile(new File(it.next()), name, 0, depth);
            if (found != null) break;
        }
        return found == null ? "" : found.getAbsolutePath();
    }

    public static String summary() {
        if (!Prefers.getBoolean("local_source_enabled", false)) return "未启用";
        ArrayList<String> dirs = stringList(loadSettings(), "scan_dirs");
        if (dirs.isEmpty()) return "未设置文件夹";
        String saved = Prefers.getString("local_source_summary");
        if (!TextUtils.isEmpty(saved)) return saved;
        return "共 " + dirs.size() + " 个目录，待扫描";
    }

    public static boolean hasFileWithin(File dir, int depth) {
        File[] files;
        if (depth <= 3 && (files = dir.listFiles()) != null) {
            for (File f : files) {
                if (f.isFile() || (f.isDirectory() && hasFileWithin(f, depth + 1))) return true;
            }
        }
        return false;
    }

    public static boolean isLocalSite(JsonObject site) {
        return locateSiteFile(getStringOrNull(site, "api"), getStringOrNull(site, "ext"), getStringOrNull(site, "homePage")) != null;
    }

    public static boolean isInternalRef(String ref) {
        String trim = ref == null ? "" : ref.trim();
        if (TextUtils.isEmpty(trim) || trim.startsWith("csp_") || trim.startsWith("assets:")) return true;
        if (!trim.endsWith(".js")) return false;
        String lower = trim.toLowerCase(Locale.US);
        return lower.endsWith(".min.js") || lower.startsWith("./lib/") || lower.startsWith("lib/") || lower.contains("/lib/");
    }

    public static boolean isSelfFile(File file, JsonObject cfg) {
        String path = file.getAbsolutePath();
        String out = outputPath(cfg);
        if (!TextUtils.isEmpty(out) && path.equals(new File(out).getAbsolutePath())) return true;
        String name = file.getName();
        if ("本地.json".equals(name)) return true;
        return name.endsWith("_settings.json");
    }

    public static String stripSuffixNum(String key) {
        int idx;
        if (TextUtils.isEmpty(key) || (idx = key.lastIndexOf('_')) <= 0 || idx >= key.length() - 1) return null;
        String suffix = key.substring(idx + 1);
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return null;
        }
        return key.substring(0, idx);
    }

    public static ArrayList<String> stringList(JsonObject obj, String key) {
        ArrayList<String> out = new ArrayList<>();
        JsonElement element = obj.get(key);
        if (element != null && element.isJsonArray()) {
            Iterator<JsonElement> it = element.getAsJsonArray().iterator();
            while (it.hasNext()) {
                JsonElement item = it.next();
                try {
                    out.add(item.isJsonPrimitive() ? item.getAsString() : item.toString());
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    public static int maxDepth(JsonObject cfg) {
        int value = 5;
        try {
            JsonElement element = cfg.get("max_depth");
            if (element != null && element.isJsonPrimitive()) value = element.getAsInt();
        } catch (Throwable ignored) {
        }
        return Math.max(0, Math.min(value, 8));
    }

    public static String md5(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] buffer = new byte[8192];
                while (true) {
                    int read = fis.read(buffer);
                    if (read <= 0) break;
                    digest.update(buffer, 0, read);
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : digest.digest()) sb.append(String.format(Locale.US, "%02x", b));
                return sb.toString();
            } finally {
                fis.close();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean moveFile(File from, File to) {
        try {
            if (from.renameTo(to)) return true;
        } catch (Throwable th) {
            DebugLogStore.add("LocalSource", "renameTo 失败，改用拷贝：" + th);
        }
        try {
            FileInputStream fis = new FileInputStream(from);
            try {
                FileOutputStream fos = new FileOutputStream(to);
                try {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        int read = fis.read(buffer);
                        if (read <= 0) break;
                        fos.write(buffer, 0, read);
                    }
                    fos.flush();
                    if (to.length() != from.length()) return false;
                    return from.delete();
                } finally {
                    fos.close();
                }
            } finally {
                fis.close();
            }
        } catch (Throwable th) {
            DebugLogStore.add("LocalSource", "源文件拷贝失败：" + th);
            return false;
        }
    }

    public static String canonPath(String path) {
        if (TextUtils.isEmpty(path)) return "";
        File file = new File(path.trim());
        try {
            return toExternal(file.getCanonicalPath());
        } catch (Throwable ignored) {
            return toExternal(file.getAbsolutePath());
        }
    }

    public static String outputDir(JsonObject cfg) {
        String out = outputPath(cfg);
        if (TextUtils.isEmpty(out)) return "";
        File parent = new File(out).getAbsoluteFile().getParentFile();
        return parent == null ? "" : parent.getAbsolutePath();
    }

    public static void collectObjects(ArrayList<JsonObject> out, JsonElement array) {
        if (array == null || !array.isJsonArray()) return;
        Iterator<JsonElement> it = array.getAsJsonArray().iterator();
        while (it.hasNext()) {
            JsonElement item = it.next();
            if (item.isJsonObject()) out.add(item.getAsJsonObject().deepCopy());
        }
    }

    public static String outputPath(JsonObject cfg) {
        String configured = getString(cfg, "output_path");
        if (!TextUtils.isEmpty(configured)) return configured;
        ArrayList<String> dirs = stringList(cfg, "scan_dirs");
        return dirs.isEmpty() ? "" : new File(dirs.get(0), "本地.json").getAbsolutePath();
    }

    public static int mergeInto(JsonObject root, String arrayKey, String keyField, JsonArray incoming, int cap) {
        int added = 0;
        if (incoming == null || incoming.isEmpty()) return 0;
        JsonArray target = (root.has(arrayKey) && root.get(arrayKey).isJsonArray()) ? root.getAsJsonArray(arrayKey) : new JsonArray();
        LinkedHashMap<String, String> baseKeys = new LinkedHashMap<>();
        Iterator<JsonElement> it = target.iterator();
        while (it.hasNext()) {
            JsonElement item = it.next();
            if (item.isJsonObject()) {
                JsonObject obj = item.getAsJsonObject();
                String key = getString(obj, keyField);
                if (!TextUtils.isEmpty(key) && stripSuffixNum(key) == null && !baseKeys.containsKey(key)) {
                    baseKeys.put(key, dedupKey(obj, keyField));
                }
            }
        }
        ArrayList<JsonElement> stale = new ArrayList<>();
        Iterator<JsonElement> it2 = target.iterator();
        while (it2.hasNext()) {
            JsonElement item = it2.next();
            if (item.isJsonObject()) {
                JsonObject obj = item.getAsJsonObject();
                String base = stripSuffixNum(getString(obj, keyField));
                String known;
                if (base != null && (known = baseKeys.get(base)) != null && known.equals(dedupKey(obj, keyField))) {
                    stale.add(item);
                }
            }
        }
        Iterator<JsonElement> it3 = stale.iterator();
        while (it3.hasNext()) target.remove(it3.next());
        if (!stale.isEmpty()) DebugLogStore.add("LocalSource", "清理历史重复源 " + stale.size() + " 条（" + keyField + "）");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        LinkedHashSet<String> dedupKeys = new LinkedHashSet<>();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Iterator<JsonElement> it4 = target.iterator();
        while (it4.hasNext()) {
            JsonElement item = it4.next();
            if (item.isJsonObject()) {
                JsonObject obj = item.getAsJsonObject();
                keys.add(getString(obj, keyField));
                dedupKeys.add(dedupKey(obj, keyField));
                String path = resolveSiteFile(obj);
                if (!TextUtils.isEmpty(path)) paths.add(path);
            }
        }
        Iterator<JsonElement> it5 = incoming.iterator();
        int skipped = 0;
        while (it5.hasNext()) {
            JsonElement item = it5.next();
            if (!item.isJsonObject() || target.size() >= cap) break;
            JsonObject copy = item.getAsJsonObject().deepCopy();
            if (!dedupKeys.contains(dedupKey(copy, keyField))) {
                String path = resolveSiteFile(copy);
                if (TextUtils.isEmpty(path) || !paths.contains(path)) {
                    String key = getString(copy, keyField);
                    if (!TextUtils.isEmpty(key)) {
                        String renamed = key;
                        int n = 1;
                        while (keys.contains(renamed)) {
                            n++;
                            renamed = key + "_" + n;
                        }
                        if (!renamed.equals(key)) copy.addProperty(keyField, renamed);
                        keys.add(renamed);
                    }
                    target.add(copy);
                    dedupKeys.add(dedupKey(copy, keyField));
                    if (!TextUtils.isEmpty(path)) paths.add(path);
                    added++;
                } else {
                    skipped++;
                }
            }
        }
        if (!root.has(arrayKey)) root.add(arrayKey, target);
        if (skipped > 0) DebugLogStore.add("LocalSource", "按源文件路径跳过重复注入 " + skipped + " 条（" + arrayKey + "）");
        return added;
    }

    public static String parentPath(String path) {
        File parent = new File(path).getAbsoluteFile().getParentFile();
        return parent == null ? "" : parent.getAbsolutePath();
    }

    public static JsonObject defaults() {
        try {
            String text = Asset.read("local_source_defaults.json");
            if (!TextUtils.isEmpty(text)) return JsonParser.parseString(text).getAsJsonObject();
        } catch (Throwable th) {
            DebugLogStore.add("LocalSource", "默认常量读取失败：" + th);
        }
        return new JsonObject();
    }

    public static ArrayList<String> candidates(String ref) {
        ArrayList<String> out = new ArrayList<>();
        String s = ref == null ? "" : ref.trim();
        if (isInternalRef(s)) return out;
        boolean needRoots;
        if (s.startsWith("file:")) {
            s = s.substring(5);
            needRoots = !s.startsWith("/") && !s.startsWith("//");
        } else if (s.startsWith("http://") || s.startsWith("https://")) {
            int idx = s.indexOf("/file/");
            if (idx < 0) return out;
            s = s.substring(idx + 6);
            needRoots = !s.startsWith("/") && !s.startsWith("//");
        } else {
            needRoots = !s.startsWith("/");
        }
        while (s.startsWith("//")) s = s.substring(1);
        if (s.startsWith("./")) s = s.substring(2);
        if (TextUtils.isEmpty(s)) return out;
        ArrayList<String> variants = new ArrayList<>();
        String decoded = s;
        try {
            decoded = Uri.decode(s);
            if (TextUtils.isEmpty(decoded)) decoded = s;
        } catch (Throwable ignored) {
            decoded = s;
        }
        variants.add(decoded);
        if (!decoded.equals(s)) variants.add(s);
        if (needRoots) {
            ArrayList<String> copy = new ArrayList<>(variants);
            Iterator<String> it = copy.iterator();
            while (it.hasNext()) {
                String cand = it.next();
                for (int i = 0; i < 3; i++) {
                    String joined = STORAGE_ROOTS[i] + "/" + cand;
                    if (!out.contains(joined)) out.add(joined);
                }
            }
        }
        Iterator<String> it = variants.iterator();
        while (it.hasNext()) {
            String cand = it.next();
            if (!out.contains(cand)) out.add(cand);
        }
        return out;
    }

    public static JsonArray defaultParses() {
        JsonArray out = new JsonArray();
        try {
            JsonObject all = defaults();
            JsonElement extra;
            if (all.has("EXTRA") && all.get("EXTRA").isJsonObject() && (extra = all.getAsJsonObject("EXTRA").get("parses")) != null && extra.isJsonArray()) {
                Iterator<JsonElement> it = extra.getAsJsonArray().iterator();
                while (it.hasNext()) {
                    JsonElement item = it.next();
                    if (item.isJsonObject()) {
                        JsonObject copy = item.getAsJsonObject().deepCopy();
                        copy.addProperty("enabled", Boolean.TRUE);
                        out.add(copy);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String stripBrackets(String s) {
        return s == null ? "" : s.replace("[", "").replace("]", "").trim();
    }

    public static String defaultString(String key) {
        try {
            JsonObject all = defaults();
            return (all.has(key) && all.get(key).isJsonPrimitive()) ? all.get(key).getAsString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String getStringOrNull(JsonObject obj, String key) {
        if (obj != null && obj.has(key)) {
            try {
                JsonElement element = obj.get(key);
                if (element != null && element.isJsonPrimitive()) return element.getAsString();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static void writeBack(JsonObject cfg, boolean force) {
        if (cfg == null) return;
        if (!force) {
            try {
                if (!Prefers.getBoolean("local_source_enabled", false)) return;
            } catch (Throwable th) {
                DebugLogStore.add("LocalSource", "本地源自动回写失败：" + th);
                return;
            }
        }
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(cfg);
        synchronized (LOCK) {
            if (json.equals(lastWritten)) return;
            long now = System.currentTimeMillis();
            if (force || now - lastWriteTime >= 1000) {
                lastWriteTime = now;
                lastWritten = json;
                String path = writeGenerated(cfg);
                if (path != null) Prefers.put("local_source_file", path);
            }
        }
    }

    public static void requestRefresh() {
        if (refreshing) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1500L);
                    } catch (Throwable ignored) {
                    }
                    requestRefresh();
                }
            }, "local-source-refresh-retry").start();
        } else {
            refreshing = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        writeBack(buildCached(true), true);
                    } catch (Throwable th) {
                        DebugLogStore.add("LocalSource", "本地源回写失败：" + th);
                    } finally {
                        refreshing = false;
                    }
                }
            }, "local-source-refresh").start();
        }
    }

    public static String bareFileName(String api, String ext, String homePage) {
        ArrayList<String> list = new ArrayList<>();
        addCandidates(list, ext);
        addCandidates(list, homePage);
        if (!isInternalRef(api)) addCandidates(list, api);
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            String item = it.next();
            String trim = item == null ? "" : item.trim();
            if (!TextUtils.isEmpty(trim) && trim.contains(".")) {
                int idx = trim.lastIndexOf('/');
                if (idx >= 0) trim = trim.substring(idx + 1);
                try {
                    trim = Uri.decode(trim);
                } catch (Throwable ignored) {
                }
                if (!TextUtils.isEmpty(trim) && trim.contains(".") && !trim.startsWith("csp_")) return trim;
            }
        }
        return null;
    }

    public static String relativize(File file, String baseDir) {
        String path = file.getAbsolutePath();
        if (!TextUtils.isEmpty(baseDir)) {
            if (path.equals(baseDir)) return "./";
            if (path.startsWith(baseDir + File.separator)) {
                return "./" + path.substring(baseDir.length() + 1);
            }
        }
        return "file://" + path;
    }

    public static boolean refExists(String ref, ArrayList<String> roots, ArrayList<String> canonPaths) {
        String trim = ref.trim();
        if (TextUtils.isEmpty(trim) || trim.startsWith("http://") || trim.startsWith("https://")) return true;
        Iterator<String> it = candidates(trim).iterator();
        while (it.hasNext()) {
            String cand = it.next();
            if (!TextUtils.isEmpty(cand)) {
                if (!cand.startsWith("/")) {
                    Iterator<String> it2 = roots.iterator();
                    while (it2.hasNext()) {
                        if (new File(it2.next(), cand).isFile()) return true;
                    }
                    for (int i = 0; i < 3; i++) {
                        if (isUnderDirs(canonPaths, normalizePath(STORAGE_ROOTS[i] + "/" + cand))) return true;
                    }
                } else if (isUnderDirs(canonPaths, normalizePath(cand))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String stripRootPrefix(String ref) {
        if (TextUtils.isEmpty(ref)) return ref;
        if (!ref.startsWith("/")) {
            String decoded = ref;
            try {
                decoded = Uri.decode(ref);
            } catch (Throwable ignored) {
            }
            try {
                Iterator<String> it = candidateRoots(loadSettings()).iterator();
                while (it.hasNext()) {
                    String name = new File(it.next()).getName();
                    if (!TextUtils.isEmpty(name)) {
                        String prefix = name + "/";
                        if (decoded.startsWith(prefix)) return decoded.substring(prefix.length());
                    }
                }
                return decoded;
            } catch (Throwable ignored) {
                return decoded;
            }
        }
        try {
            Iterator<String> it = candidateRoots(loadSettings()).iterator();
            while (it.hasNext()) {
                String root = it.next();
                if (!root.endsWith("/")) root = root + "/";
                if (ref.startsWith(root)) return ref.substring(root.length());
            }
            for (int i = 0; i < 3; i++) {
                String prefix = STORAGE_ROOTS[i] + "/";
                if (ref.startsWith(prefix)) return ref.substring(prefix.length());
            }
        } catch (Throwable ignored) {
        }
        return ref;
    }

    public static boolean getBool(JsonObject obj, String key, boolean def) {
        try {
            JsonElement element = obj.get(key);
            if (element != null && element.isJsonPrimitive()) return element.getAsBoolean();
        } catch (Throwable ignored) {
        }
        return def;
    }

    public static String relativePath(File base, File file) {
        String basePath = base.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        if (path.equals(basePath)) return "";
        if (path.startsWith(basePath + "/")) return path.substring(basePath.length() + 1);
        return null;
    }
    public static JsonObject buildCached(boolean z) {
        JsonObject jsonObject;
        synchronized (LOCK) {
            try {
                long currentTimeMillis = System.currentTimeMillis();
                if (!z && (jsonObject = cache) != null && currentTimeMillis - cacheTime < 3000) {
                    return jsonObject;
                }
                JsonObject o0 = buildConfig();
                cache = o0;
                cacheTime = currentTimeMillis;
                return o0;
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public static int expandPaths(JsonElement jsonElement, String str) {
        int i2;
        String absolutePath;
        if (jsonElement == null || !jsonElement.isJsonArray()) {
            return 0;
        }
        Iterator it = jsonElement.getAsJsonArray().iterator();
        int i3 = 0;
        while (it.hasNext()) {
            JsonElement jsonElement2 = (JsonElement) it.next();
            if (jsonElement2.isJsonObject()) {
                JsonObject asJsonObject = jsonElement2.getAsJsonObject();
                String[] strArr = {"api", "ext", "jar", "homePage", "url"};
                for (int i4 = 0; i4 < 5; i4++) {
                    String str2 = strArr[i4];
                    JsonElement jsonElement3 = asJsonObject.get(str2);
                    if (jsonElement3 != null && jsonElement3.isJsonPrimitive() && jsonElement3.getAsJsonPrimitive().isString()) {
                        String asString = jsonElement3.getAsString();
                        if (asString.startsWith("./") || asString.startsWith("../")) {
                            try {
                                absolutePath = new File(str, asString).getCanonicalPath();
                            } catch (Throwable unused) {
                                absolutePath = new File(str, asString).getAbsolutePath();
                            }
                            if (!"jar".equals(str2)) {
                                absolutePath = ("file://" + absolutePath);
                            }
                            asJsonObject.addProperty(str2, absolutePath);
                            i2 = 1;
                            i3 += i2;
                        }
                    }
                    i2 = 0;
                    i3 += i2;
                }
            }
        }
        return i3;
    }

    public static ArrayList defaultScanDirs() {
        JsonElement jsonElement;
        ArrayList arrayList = new ArrayList();
        try {
            JsonObject c2 = defaults();
            if (c2.has("DEFAULTS") && c2.get("DEFAULTS").isJsonObject() && (jsonElement = c2.getAsJsonObject("DEFAULTS").get("scan_dirs")) != null && jsonElement.isJsonArray()) {
                Iterator it = jsonElement.getAsJsonArray().iterator();
                while (it.hasNext()) {
                    JsonElement jsonElement2 = (JsonElement) it.next();
                    if (jsonElement2.isJsonPrimitive()) {
                        arrayList.add(jsonElement2.getAsString());
                    }
                }
            }
        } catch (Throwable unused) {
        }
        return arrayList;
    }

    public static void expandConfigPaths(JsonObject jsonObject, String str) {
        String str2 = "";
        try {
            if (!TextUtils.isEmpty(str)) {
                String trim = str.trim();
                if (trim.startsWith("file:")) {
                    String substring = trim.substring(trim.indexOf("file:") + 5);
                    while (substring.startsWith("/")) {
                        substring = substring.substring(1);
                    }
                    str2 = parentPath("/" + Uri.decode(substring));
                } else if (trim.startsWith("/")) {
                    str2 = parentPath(trim);
                } else if (trim.startsWith("http://127.0.0.1") && trim.contains("/file/")) {
                    String decode = Uri.decode(trim.substring(trim.indexOf("/file/") + 6));
                    if (!decode.startsWith("/")) {
                        decode = "/".concat(decode);
                    }
                    str2 = parentPath(decode);
                }
            }
        } catch (Throwable unused) {
        }
        if (TextUtils.isEmpty(str2)) {
            str2 = outputDir(loadSettings());
        }
        if (TextUtils.isEmpty(str2)) {
            return;
        }
        int j0 = expandPaths(jsonObject.get("lives"), str2) + expandPaths(jsonObject.get("sites"), str2);
        if (j0 > 0) {
            DebugLogStore.add("LocalSource", "相对路径展开 " + j0 + " 处（基准 " + str2 + "）");
        }
    }

    public static String normalizePath(String str) {
        String trim = str == null ? "" : str.trim();
        try {
            trim = Uri.decode(trim);
        } catch (Throwable unused) {
        }
        String replace = trim.replace('\\', '/');
        boolean startsWith = replace.startsWith("/");
        ArrayList arrayList = new ArrayList();
        for (String str2 : replace.split("/")) {
            if (!TextUtils.isEmpty(str2) && !".".equals(str2)) {
                if (!"..".equals(str2)) {
                    arrayList.add(str2);
                } else if (!arrayList.isEmpty()) {
                    arrayList.remove(arrayList.size() - 1);
                }
            }
        }
        return toExternal((startsWith ? "/" : "") + TextUtils.join("/", arrayList));
    }

    public static int rewriteRefs(JsonElement jsonElement, String str, String str2, JsonObject jsonObject) {
        if (jsonElement == null || !jsonElement.isJsonArray()) {
            return 0;
        }
        Iterator it = jsonElement.getAsJsonArray().iterator();
        int i2 = 0;
        while (it.hasNext()) {
            JsonElement jsonElement2 = (JsonElement) it.next();
            if (jsonElement2.isJsonObject()) {
                JsonObject asJsonObject = jsonElement2.getAsJsonObject();
                String[] strArr = {"ext", "homePage", "api"};
                int i3 = 0;
                while (true) {
                    if (i3 < 3) {
                        String str3 = strArr[i3];
                        String e0 = getStringOrNull(asJsonObject, str3);
                        if (!TextUtils.isEmpty(e0)) {
                            ArrayList arrayList = new ArrayList();
                            Iterator it2 = candidates(e0).iterator();
                            while (it2.hasNext()) {
                                String str4 = (String) it2.next();
                                if (!TextUtils.isEmpty(str4)) {
                                    if (!str4.startsWith("/")) {
                                        Iterator it3 = candidateRoots(jsonObject).iterator();
                                        while (it3.hasNext()) {
                                            String str5 = (String) it3.next();
                                            if (!TextUtils.isEmpty(str5)) {
                                                if (str5.endsWith("/")) {
                                                    str5 = str5.substring(0, str5.length() - 1);
                                                }
                                                String r = str5 + "/" + str4;
                                                if (!arrayList.contains(r)) {
                                                    arrayList.add(r);
                                                }
                                            }
                                        }
                                    } else if (!arrayList.contains(str4)) {
                                        arrayList.add(str4);
                                    }
                                }
                            }
                            Iterator it4 = arrayList.iterator();
                            while (it4.hasNext()) {
                                if (((String) it4.next()).equals(str)) {
                                    asJsonObject.addProperty(str3, str2);
                                    i2++;
                                    break;
                                }
                            }
                        }
                        i3++;
                    }
                }
            }
        }
        return i2;
    }

    public static ArrayList collectNames(boolean z) {
        JsonObject q0 = loadSettings();
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        outputDir(q0);
        Iterator it = enabledDirs(q0).iterator();
        while (it.hasNext()) {
            File file = new File((String) it.next());
            if (file.isDirectory()) {
                collectNamesRec(file, 0, z, linkedHashSet, q0);
            }
        }
        ArrayList arrayList = new ArrayList(linkedHashSet);
        if (!z) {
            arrayList.sort(BY_NAME_STR);
        }
        return arrayList.size() > 3000 ? new ArrayList(arrayList.subList(0, 3000)) : arrayList;
    }

    public static int rewriteMovedRefs(File file, File file2, String str, JsonObject jsonObject) {
        String str2;
        String absolutePath = file.getAbsolutePath();
        ArrayList arrayList = new ArrayList();
        Iterator it = enabledDirs(jsonObject).iterator();
        while (it.hasNext()) {
            File file3 = new File((String) it.next());
            if (file3.isDirectory()) {
                collectJsonFiles(file3, arrayList, 0, jsonObject);
            }
        }
        String a0 = outputPath(jsonObject);
        if (!TextUtils.isEmpty(a0)) {
            File file4 = new File(a0);
            if (file4.isFile() && !arrayList.contains(file4)) {
                arrayList.add(file4);
            }
        }
        Iterator it2 = arrayList.iterator();
        int i2 = 0;
        while (it2.hasNext()) {
            File file5 = (File) it2.next();
            try {
                FileInputStream fileInputStream = new FileInputStream(file5);
                try {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] bArr = new byte[8192];
                    while (true) {
                        int read = fileInputStream.read(bArr);
                        if (read <= 0) {
                            break;
                        }
                        byteArrayOutputStream.write(bArr, 0, read);
                    }
                    str2 = byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
                    fileInputStream.close();
                } catch (Throwable th) {
                    try {
                        fileInputStream.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                    throw th;
                }
            } catch (Throwable unused) {
                str2 = null;
            }
            try {
                if (!TextUtils.isEmpty(str2) && str2.length() <= 8388608) {
                    String name = file2.getName();
                    if (str2.contains(name) || str2.contains(Uri.encode(name))) {
                        JsonObject asJsonObject = JsonParser.parseString(str2).getAsJsonObject();
                        int l0 = rewriteRefs(asJsonObject.get("sites"), absolutePath, str, jsonObject) + rewriteRefs(asJsonObject.get("lives"), absolutePath, str, jsonObject);
                        if (l0 != 0) {
                            writeText(file5, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(asJsonObject));
                            i2 += l0;
                            DebugLogStore.add("LocalSource", "已改写 json 指向：" + file5.getAbsolutePath() + "（" + l0 + " 处）");
                        }
                    }
                }
            } catch (Throwable th3) {
                DebugLogStore.add("LocalSource", "改写 json 失败（跳过 " + file5.getName() + "）：" + th3);
            }
        }
        return i2;
    }

    public static String toExternal(String str) {
        String replace = str.replace('\\', '/');
        for (int i2 = 0; i2 < 5; i2++) {
            String str2 = STORAGE_PREFIXES[i2];
            if (replace.startsWith(str2)) {
                return "#external/".concat(replace.substring(str2.length()));
            }
        }
        return "/sdcard".equals(replace) ? "#external/" : replace;
    }

    public static void saveSettings(JsonObject jsonObject) {
        try {
            Prefers.put("local_source_settings", jsonObject.toString());
            cache = null;
        } catch (Throwable th) {
            DebugLogStore.add("LocalSource", "设置保存失败：" + th);
        }
    }

    public static String dirDisplayName(String str) {
        if (TextUtils.isEmpty(str)) {
            return "";
        }
        String d0 = stripBrackets(str != null ? str.replaceAll("\\[[^\\]]*\\]", "") : "");
        if (!TextUtils.isEmpty(d0)) {
            return d0;
        }
        ArrayList arrayList = new ArrayList();
        if (str != null) {
            Matcher matcher = Pattern.compile("\\[[^\\]]+\\]").matcher(str);
            while (matcher.find()) {
                arrayList.add(matcher.group());
            }
        }
        return !arrayList.isEmpty() ? stripBrackets((String) arrayList.get(0)) : str.trim();
    }

    /* JADX WARN: Code restructure failed: missing block: B:65:0x0208, code lost:
    
        if (r7.contains(r3) == false) goto L77;
     */
    /* JADX WARN: Removed duplicated region for block: B:43:0x0182 A[LOOP:2: B:41:0x017c->B:43:0x0182, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:47:0x01af  */
    /* JADX WARN: Removed duplicated region for block: B:51:0x01d6  */
    /* JADX WARN: Removed duplicated region for block: B:64:0x0200  */
    /* JADX WARN: Removed duplicated region for block: B:71:0x021e A[LOOP:4: B:69:0x0218->B:71:0x021e, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:74:0x01b2  */
    /* JADX WARN: Removed duplicated region for block: B:79:0x0108 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:89:0x013b  */
    public static JsonObject buildConfig() {
    long startTime = System.currentTimeMillis();
    JsonObject settings = loadSettings();
    ScanCtx ctx = new ScanCtx(); // new te(2, false)
    String outDir = outputDir(settings);

    // ---- Stage 1: scan every enabled dir with the recursive scanner B0 ----
    ArrayList<String> dirs = enabledDirs(settings);
    for (String dir : dirs) {
        File dirFile = new File(dir);
        if (!dirFile.isDirectory()) {
            ctx.note(dir, "目录不存在或不可读");
            continue;
        }
        if (stringList(settings, "blocked_dirs").contains(dirFile.getName())) {
            continue;
        }
        scanFile(dirFile, 0, false, false, false, false, ctx, settings, outDir);
    }

    // ---- Stage 2: collect candidate sites: PINNED + scanned + MANUAL, dedup by key ----
    ArrayList<JsonObject> all = new ArrayList<>();
    JsonObject defaults = defaults();
    collectObjects(all, defaults.get("PINNED_SITES"));
    all.addAll((ArrayList) ctx.e);
    collectObjects(all, defaults.get("MANUAL_SITES"));

    ArrayList<JsonObject> sites = new ArrayList<>();
    LinkedHashMap<String, JsonObject> byKey = new LinkedHashMap<>();
    LinkedHashSet<String> names = new LinkedHashSet<>();

    for (JsonObject rawSite : all) {
        JsonObject site = rawSite.deepCopy();
        String key = getString(site, "key");
        if (TextUtils.isEmpty(key)) {
            sites.add(site);
            names.add(getString(site, "name"));
            continue;
        }
        JsonObject existing = byKey.get(key);
        if (existing == null) {
            byKey.put(key, site);
            sites.add(site);
            names.add(getString(site, "name"));
            continue;
        }
        // Duplicate key: if both point to local files with equal name and equal
        // content (same length and same md5 via md5()), fold the duplicate away.
        String newPath = getString(site, "#path");
        String oldPath = getString(existing, "#path");
        boolean folded = false;
        if (!TextUtils.isEmpty(newPath) && !TextUtils.isEmpty(oldPath)) {
            boolean sameName;
            try {
                sameName = new File(newPath).getName().equals(new File(oldPath).getName());
            } catch (Throwable th) {
                sameName = false;
            }
            if (sameName) {
                boolean sameContent;
                try {
                    File newFile = new File(newPath);
                    File oldFile = new File(oldPath);
                    sameContent = newFile.isFile() && oldFile.isFile()
                            && newFile.length() == oldFile.length()
                            && md5(newFile).equals(md5(oldFile));
                } catch (Throwable th) {
                    sameContent = false;
                }
                if (sameContent) {
                    DebugLogStore.add("LocalSource", "重复源折叠：" + new File(newPath).getName()
                            + " 与 " + new File(oldPath).getName() + " 同名同内容，只保留一条");
                    folded = true;
                }
            }
        }
        if (folded) {
            continue;
        }
        // Otherwise keep it under a suffixed key: key_1, key_2, ... until free.
        String newKey = (key + "_1");
        int suffix = 1;
        while (byKey.containsKey(newKey)) {
            suffix = suffix + 1;
            newKey = key + "_" + suffix;
        }
        // Build a unique display name: prefer the bracket tags of "#base"
        // (joined with '+'); fall back to a numeric suffix.
        String name = getString(site, "name");
        String base = getString(site, "#base");
        ArrayList<String> tags = new ArrayList<>();
        if (base != null) {
            Matcher matcher = Pattern.compile("\\[[^\\]]+\\]").matcher(base);
            while (matcher.find()) {
                tags.add(matcher.group());
            }
        }
        StringBuilder tagBuilder = new StringBuilder();
        for (String tag : tags) {
            String cleaned = stripBrackets(tag);
            if (TextUtils.isEmpty(cleaned)) {
                continue;
            }
            if (tagBuilder.length() > 0) {
                tagBuilder.append('+');
            }
            tagBuilder.append(cleaned);
        }
        String joined = tagBuilder.toString();
        String newName;
        if (!TextUtils.isEmpty(joined) && !names.contains(newName = insertTag(name, joined))) {
            // newName = name with the joined base tags inserted by C0
        } else {
            int n = Math.max(suffix, 1);
            String candidate = insertTag(name, String.valueOf(n));
            while (names.contains(candidate)) {
                n = n + 1;
                candidate = insertTag(name, String.valueOf(n));
            }
            newName = candidate;
        }
        site.addProperty("key", newKey);
        site.addProperty("name", newName);
        names.add(newName);
        byKey.put(newKey, site);
        sites.add(site);
    }

    // ---- Stage 3: optionally merge same-name sites, keeping the best engine ----
    if (getBool(settings, "merge_same_name", false)) {
        LinkedHashMap<String, JsonObject> byName = new LinkedHashMap<>();
        int merged = 0;
        for (JsonObject site : sites) {
            String rawName = getString(site, "name");
            String baseName = rawName == null ? "" : rawName.replaceAll("\\([^()]*\\)\\s*$", "").trim();
            JsonObject prev = byName.get(baseName);
            if (prev == null) {
                byName.put(baseName, site);
            } else {
                merged++;
                if (sitePriority(site) > sitePriority(prev)) {
                    byName.put(baseName, site);
                }
            }
        }
        if (merged > 0) {
            DebugLogStore.add("LocalSource", "同名多引擎合并：折叠 " + merged + " 条");
        }
        sites = new ArrayList<>(byName.values());
    }

    // ---- Stage 4: strip internal "#"-prefixed keys from every site ----
    for (JsonObject site : sites) {
        ArrayList<String> hashKeys = new ArrayList<>();
        for (String k : site.keySet()) {
            if (k.startsWith("#")) {
                hashKeys.add(k);
            }
        }
        for (String k : hashKeys) {
            site.remove(k);
        }
    }

    // ---- Stage 5: prune bracket tags in names that are not known folder names ----
    LinkedHashSet knownTags = (LinkedHashSet) ctx.i;
    int pruned = 0;
    for (JsonObject site : sites) {
        String name = getString(site, "name");
        if (TextUtils.isEmpty(name)) {
            continue;
        }
        Matcher matcher = BRACKET.matcher(name);
        StringBuilder newNameBuilder = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            String tag = stripBrackets(matcher.group(1));
            if (TextUtils.isEmpty(tag)) {
                continue;
            }
            boolean known = knownTags.contains(tag);
            if (!known) {
                String tagLower = tag.toLowerCase(Locale.US);
                for (Object element : knownTags) {
                    if (((String) element).toLowerCase(Locale.US).equals(tagLower)) {
                        known = true;
                        break;
                    }
                }
            }
            if (!known) {
                continue;
            }
            newNameBuilder.append(name, lastEnd, matcher.start());
            newNameBuilder.append(name, matcher.start(), matcher.end());
            lastEnd = matcher.end();
        }
        newNameBuilder.append(name.substring(lastEnd));
        String cleanedName = newNameBuilder.toString().trim();
        if (!TextUtils.isEmpty(cleanedName) && !TextUtils.equals(cleanedName, name)) {
            site.addProperty("name", cleanedName);
            pruned++;
        }
    }
    if (pruned > 0) {
        DebugLogStore.add("LocalSource", "分类收口：删掉 " + pruned + " 条源里非文件夹名的分类");
    }

    // ---- Stage 6: assemble the output config ----
    JsonArray lives = new JsonArray();
    String liveUrl = liveUrl();
    if (!TextUtils.isEmpty(liveUrl)) {
        JsonElement defaultLives = defaults.get("DEFAULT_LIVES");
        if (defaultLives != null && defaultLives.isJsonArray()) {
            for (JsonElement element : defaultLives.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject live = element.getAsJsonObject().deepCopy();
                live.addProperty("url", liveUrl);
                lives.add(live);
            }
        }
    }
    for (Object liveObj : (ArrayList) ctx.f) {
        lives.add((JsonObject) liveObj);
    }

    JsonObject result = new JsonObject();
    String spider = spiderJarPath();
    if (!TextUtils.isEmpty(spider)) {
        result.addProperty("spider", relativize(new File(spider), outDir));
    }
    result.addProperty("logo", defaultString("CONFIG_LOGO"));
    result.addProperty("wallpaper", defaultString("CONFIG_WALLPAPER"));
    result.add("lives", lives);
    JsonArray sitesArray = new JsonArray();
    for (JsonObject site : sites) {
        sitesArray.add(site);
    }
    result.add("sites", sitesArray);

    // EXTRA block from defaults, with "parses" rebuilt from enabled parse entries.
    JsonObject extra = new JsonObject();
    try {
        if (defaults.has("EXTRA") && defaults.get("EXTRA").isJsonObject()) {
            extra = defaults.getAsJsonObject("EXTRA").deepCopy();
        }
    } catch (Throwable ignored) {
    }
    JsonElement parseEntriesElement = settings.get("parse_entries");
    JsonArray parseEntries = (parseEntriesElement != null && parseEntriesElement.isJsonArray())
            ? parseEntriesElement.getAsJsonArray() : defaultParses();
    JsonArray parses = new JsonArray();
    for (JsonElement element : parseEntries) {
        if (!element.isJsonObject()) {
            continue;
        }
        JsonObject entry = element.getAsJsonObject();
        if (!getBool(entry, "enabled", true)) {
            continue;
        }
        JsonObject copy = entry.deepCopy();
        copy.remove("enabled");
        parses.add(copy);
    }
    extra.add("parses", parses);
    for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
        result.add(entry.getKey(), entry.getValue());
    }

    // ---- Stage 7: summary + diagnosis strings, prefs, and log lines ----
    long elapsed = System.currentTimeMillis() - startTime;
    StringBuilder summaryBuilder = new StringBuilder("站点 ");
    summaryBuilder.append(sites.size());
    summaryBuilder.append(" · 直播 ").append(lives.size());
    summaryBuilder.append(" · 文件 ").append(ctx.a);
    summaryBuilder.append(" · 命中 ").append(ctx.b);
    summaryBuilder.append(" · 跳过 ").append(ctx.c);
    summaryBuilder.append(" · ").append(elapsed).append("ms");
    summaryBuilder.append(ctx.d ? "（已截断）" : "");
    String summary = summaryBuilder.toString();
    Prefers.put("local_source_summary", summary);
    lastSiteCount = sites.size();

    ArrayList<String> scanExtensions = stringList(settings, "scan_extensions");
    StringBuilder diagnosisBuilder = new StringBuilder("扫描目录 ");
    diagnosisBuilder.append(enabledDirs(settings).size());
    diagnosisBuilder.append(" 个 · 文件 ").append(ctx.a);
    diagnosisBuilder.append(" · 命中 ").append(ctx.b);
    diagnosisBuilder.append(" · 目录中实际类型：");
    LinkedHashSet foundTypes = (LinkedHashSet) ctx.h;
    String typesText;
    if (foundTypes.isEmpty()) {
        typesText = "未发现可识别类型";
    } else {
        ArrayList<String> typeList = new ArrayList<>(foundTypes);
        if (typeList.size() <= 12) {
            typesText = TextUtils.join("、", typeList);
        } else {
            typesText = TextUtils.join("、", typeList.subList(0, 12))
                    + "…(共 " + typeList.size() + " 种)";
        }
    }
    diagnosisBuilder.append(typesText);
    diagnosisBuilder.append(" · 当前勾选类型：");
    diagnosisBuilder.append(scanExtensions.isEmpty()
            ? "全部（未勾选）" : TextUtils.join("、", scanExtensions));
    String diagnosis = diagnosisBuilder.toString();
    Prefers.put("local_source_diagnosis", diagnosis);

    DebugLogStore.add("LocalSource", "扫描完成：" + summary);
    DebugLogStore.add("LocalSource", "扫描诊断：" + diagnosis);
    for (String dir : enabledDirs(settings)) {
        DebugLogStore.add("LocalSource", ("扫描目录：" + dir + "（基准 " + outDir + "）"));
    }
    if (!((ArrayList) ctx.j).isEmpty()) {
        DebugLogStore.add("LocalSource", "跳过明细：" + TextUtils.join("；", (ArrayList) ctx.j));
    }
    return result;
}

    public static LinkedHashMap groupMap(File file, JsonObject jsonObject) {
        File file2;
        LinkedHashMap linkedHashMap = new LinkedHashMap();
        Iterator it = enabledDirs(jsonObject).iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            String str = (String) it.next();
            String i0 = relativePath(new File(str), file);
            if (!TextUtils.isEmpty(i0)) {
                int indexOf = i0.indexOf(47);
                if (indexOf <= 0) {
                    break;
                }
                file2 = new File(str, i0.substring(0, indexOf));
                if (file2.isDirectory()) {
                    break;
                }
            }
        }
        file2 = null;
        String A = file2 != null ? tagFlags(file2.getName()) : null;
        addGroupDirs(file2, jsonObject, linkedHashMap);
        Iterator it2 = enabledDirs(jsonObject).iterator();
        while (it2.hasNext()) {
            File[] listFiles = new File((String) it2.next()).listFiles();
            if (listFiles != null) {
                for (File file3 : listFiles) {
                    if (file3.isDirectory() && !file3.equals(file2) && A != null && A.equals(tagFlags(file3.getName()))) {
                        addGroupDirs(file3, jsonObject, linkedHashMap);
                    }
                }
            }
        }
        return linkedHashMap;
    }

    public static void saveExtensions(ArrayList arrayList) {
        JsonObject q0 = loadSettings();
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        Iterator it = arrayList.iterator();
        while (it.hasNext()) {
            String str = (String) it.next();
            if (Arrays.asList(EXTENSIONS).contains(str)) {
                linkedHashSet.add(str);
            }
        }
        q0.add("scan_extensions", toJsonArray(new ArrayList(linkedHashSet)));
        saveSettings(q0);
    }

    public static void collectNamesRec(File file, int i2, boolean z, LinkedHashSet linkedHashSet, JsonObject jsonObject) {
        File[] listFiles;
        if (i2 > maxDepth(jsonObject) || linkedHashSet.size() >= 3000 || (listFiles = file.listFiles()) == null) {
            return;
        }
        Arrays.sort(listFiles, BY_NAME);
        for (File file2 : listFiles) {
            if (linkedHashSet.size() >= 3000) {
                return;
            }
            if (file2.isDirectory()) {
                if (z) {
                    linkedHashSet.add(file2.getName());
                }
                collectNamesRec(file2, i2 + 1, z, linkedHashSet, jsonObject);
            } else if (!z && file2.isFile() && !isSelfFile(file2, jsonObject)) {
                linkedHashSet.add(file2.getName());
            }
        }
    }

    public static JsonObject loadSettings() {
        JsonObject jsonObject = new JsonObject();
        try {
            JsonObject c2 = defaults();
            if (c2.has("DEFAULTS") && c2.get("DEFAULTS").isJsonObject()) {
                jsonObject = c2.getAsJsonObject("DEFAULTS").deepCopy();
            }
        } catch (Throwable unused) {
        }
        jsonObject.addProperty("output_path", "");
        jsonObject.add("scan_dirs", new JsonArray());
        jsonObject.add("scan_enabled_dirs", new JsonArray());
        jsonObject.add("scan_extensions", new JsonArray());
        if (!jsonObject.has("scan_mode")) {
            jsonObject.addProperty("scan_mode", "source");
        }
        jsonObject.add("blocked_dirs", new JsonArray());
        if (!jsonObject.has("excluded_files")) {
            jsonObject.add("excluded_files", toJsonArray2(new String[]{"config.php", "FileExplorer.php", "index.php", "ss.json", "start.php", "start.py", "T4Proxy.php", "test_runner.php"}));
        }
        if (!jsonObject.has("parse_entries")) {
            jsonObject.add("parse_entries", defaultParses());
        }
        if (!jsonObject.has("replace_sites")) {
            jsonObject.addProperty("replace_sites", Boolean.FALSE);
        }
        if (!jsonObject.has("merge_same_name")) {
            jsonObject.addProperty("merge_same_name", Boolean.FALSE);
        }
        jsonObject.addProperty("decode_base64", Boolean.TRUE);
        jsonObject.addProperty("max_depth", 5);
        try {
            String D = Prefers.getString("local_source_settings");
            if (!TextUtils.isEmpty(D)) {
                JsonElement parseString = JsonParser.parseString(D);
                if (parseString.isJsonObject()) {
                    for (Map.Entry entry : parseString.getAsJsonObject().entrySet()) {
                        jsonObject.add((String) entry.getKey(), ((JsonElement) entry.getValue()).deepCopy());
                    }
                }
            }
        } catch (Throwable th) {
            DebugLogStore.add("LocalSource", "设置读取失败：" + th);
        }
        if (".git,.idea,labeditor,lib,pycache,webview,道长,全能王,直播转点播辅助文件".equals(TextUtils.join(",", stringList(jsonObject, "blocked_dirs")))) {
            jsonObject.add("blocked_dirs", new JsonArray());
        }
        if (stringList(jsonObject, "scan_dirs").isEmpty()) {
            String D2 = Prefers.getString("local_source_dir");
            if (!TextUtils.isEmpty(D2)) {
                jsonObject.add("scan_dirs", toJsonArray2(new String[]{D2}));
                jsonObject.add("scan_enabled_dirs", toJsonArray2(new String[]{D2}));
            }
        }
        int i2 = 0;
        if (!getBool(jsonObject, "local_source_path_scope_fixed", false)) {
            jsonObject.addProperty("local_source_path_scope_fixed", Boolean.TRUE);
            try {
                ArrayList k2 = defaultScanDirs();
                if (!k2.isEmpty()) {
                    ArrayList U = stringList(jsonObject, "scan_dirs");
                    ArrayList U2 = stringList(jsonObject, "scan_enabled_dirs");
                    if (U.size() > 1 && U2.size() > 1) {
                        LinkedHashSet linkedHashSet = new LinkedHashSet();
                        Iterator it = k2.iterator();
                        while (it.hasNext()) {
                            String Y = canonPath((String) it.next());
                            if (!TextUtils.isEmpty(Y)) {
                                linkedHashSet.add(Y);
                            }
                        }
                        if (!linkedHashSet.isEmpty()) {
                            ArrayList arrayList = new ArrayList();
                            Iterator it2 = U2.iterator();
                            int i3 = 0;
                            while (it2.hasNext()) {
                                String str = (String) it2.next();
                                String Y2 = canonPath(str);
                                if (TextUtils.isEmpty(Y2) || !linkedHashSet.contains(Y2) || U2.size() - i3 <= 1) {
                                    arrayList.add(str);
                                } else {
                                    i3++;
                                }
                            }
                            if (i3 != 0) {
                                jsonObject.add("scan_enabled_dirs", toJsonArray(intersect(U, arrayList)));
                                i2 = i3;
                            }
                        }
                    }
                }
            } catch (Throwable th2) {
                DebugLogStore.add("LocalSource", "扫描路径收口失败：" + th2);
            }
            saveSettings(jsonObject);
            if (i2 > 0) {
                DebugLogStore.add("LocalSource", "扫描口径收口：取消勾选内置默认目录 " + i2 + " 个（只扫用户自己添加的路径）");
            }
        }
        return jsonObject;
    }

    public static void findByName(File file, String str, ArrayList arrayList, int i2) {
        File[] listFiles;
        if (i2 > 4 || arrayList.size() > 1 || (listFiles = file.listFiles()) == null) {
            return;
        }
        for (File file2 : listFiles) {
            if (file2.isDirectory()) {
                findByName(file2, str, arrayList, i2 + 1);
            } else if (file2.getName().toLowerCase().equals(str)) {
                arrayList.add(file2);
            }
        }
    }

    public static JsonObject newSite(String str, String str2, String str3, boolean z, int i2) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("key", str);
        jsonObject.addProperty("name", str);
        jsonObject.addProperty("type", 3);
        jsonObject.addProperty("api", str2);
        jsonObject.addProperty("searchable", 1);
        jsonObject.addProperty("quickSearch", 1);
        jsonObject.addProperty("filterable", 1);
        if (i2 == 1) {
            jsonObject.addProperty("changeable", 1);
        }
        if (z) {
            jsonObject.addProperty("switchable", 1);
        }
        if (!TextUtils.isEmpty(str3)) {
            jsonObject.addProperty("ext", str3);
        }
        if (!TextUtils.isEmpty(null)) {
            jsonObject.addProperty("jar", (String) null);
        }
        return jsonObject;
    }

    public static void addGroupDirs(File file, JsonObject jsonObject, LinkedHashMap linkedHashMap) {
        if (file == null || !file.isDirectory()) {
            return;
        }
        ArrayList U = stringList(jsonObject, "blocked_dirs");
        File[] listFiles = file.listFiles();
        if (listFiles == null) {
            return;
        }
        for (File file2 : listFiles) {
            if (file2.isDirectory() && !U.contains(file2.getName()) && hasFileWithin(file2, 0)) {
                String o2 = dirDisplayName(file2.getName());
                if (!TextUtils.isEmpty(o2) && !linkedHashMap.containsKey(o2)) {
                    linkedHashMap.put(o2, file2.getAbsolutePath());
                }
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:15:0x0066 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:18:? A[LOOP:0: B:7:0x0019->B:18:?, LOOP_END, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static File locateSiteFile(String api, String ext, String homePage) {
        try {
            ArrayList<String> list = new ArrayList<>();
            addCandidates(list, ext);
            addCandidates(list, homePage);
            if (!isInternalRef(api)) addCandidates(list, api);
            Iterator<String> it = list.iterator();
            while (it.hasNext()) {
                String cand = it.next();
                if (!TextUtils.isEmpty(cand)) {
                    File file = new File(cand);
                    if (file.isAbsolute()) {
                        if (file.isFile()) return file;
                    } else {
                        Iterator<String> it2 = candidateRoots(loadSettings()).iterator();
                        while (it2.hasNext()) {
                            File f2 = new File(it2.next(), cand);
                            if (f2.isFile()) return f2;
                        }
                    }
                }
            }
            String name = bareFileName(api, ext, homePage);
            if (!TextUtils.isEmpty(name)) return findUniqueFile(name);
            return null;
        } catch (Throwable th) {
            DebugLogStore.add("LocalSource", "定位本地源文件失败：" + th);
            return null;
        }
    }

    public static void collectJars(File file, int i2, int i3, ArrayList arrayList) {
        File[] listFiles;
        if (i2 > i3 || arrayList.size() >= 3000 || (listFiles = file.listFiles()) == null) {
            return;
        }
        Arrays.sort(listFiles, BY_NAME);
        for (File file2 : listFiles) {
            if (arrayList.size() >= 3000) {
                return;
            }
            if (file2.isDirectory()) {
                collectJars(file2, i2 + 1, i3, arrayList);
            } else if (file2.isFile() && file2.getName().toLowerCase(Locale.US).endsWith(".jar")) {
                arrayList.add(file2.getAbsolutePath());
            }
        }
    }

    public static String siteSortKey(JsonObject jsonObject) {
        String v0 = jsonObject == null ? null : firstNonEmpty(getStringOrNull(jsonObject, "api"), getStringOrNull(jsonObject, "ext"), getStringOrNull(jsonObject, "homePage"));
        if (TextUtils.isEmpty(v0)) {
            return "";
        }
        String x0 = getString(jsonObject, "name");
        StringBuilder sb = new StringBuilder();
        sb.append(v0);
        sb.append("\u0000");
        sb.append(x0 != null ? x0.trim() : "");
        return sb.toString();
    }

    public static void collectJsonFiles(File file, ArrayList arrayList, int i2, JsonObject jsonObject) {
        File[] listFiles;
        if (i2 <= 3 && (listFiles = file.listFiles()) != null) {
            ArrayList U = stringList(jsonObject, "blocked_dirs");
            for (File file2 : listFiles) {
                if (file2.isDirectory()) {
                    if (!U.contains(file2.getName())) {
                        collectJsonFiles(file2, arrayList, i2 + 1, jsonObject);
                    }
                } else if (file2.getName().toLowerCase(Locale.US).endsWith(".json") && !arrayList.contains(file2)) {
                    arrayList.add(file2);
                }
            }
        }
    }

    public static ArrayList candidateRoots(JsonObject jsonObject) {
        ArrayList arrayList = new ArrayList();
        String Z = outputDir(jsonObject);
        if (!TextUtils.isEmpty(Z)) {
            arrayList.add(Z);
        }
        Iterator it = enabledDirs(jsonObject).iterator();
        while (it.hasNext()) {
            String str = (String) it.next();
            if (!TextUtils.isEmpty(str) && !arrayList.contains(str)) {
                arrayList.add(str);
            }
        }
        return arrayList;
    }

    public static void addCandidates(ArrayList arrayList, String str) {
        Iterator it = candidates(str).iterator();
        while (it.hasNext()) {
            String str2 = (String) it.next();
            if (!arrayList.contains(str2)) {
                arrayList.add(str2);
            }
        }
    }

    public static String firstNonEmpty(String str, String str2, String str3) {
        String[] strArr = {str2, str3, str};
        for (int i2 = 0; i2 < 3; i2++) {
            String str4 = strArr[i2];
            if (!TextUtils.isEmpty(str4)) {
                ArrayList c0 = candidates(str4);
                if (!c0.isEmpty()) {
                    return stripRootPrefix((String) c0.get(0));
                }
            }
        }
        return null;
    }

    public static void collectExtensions(File file, int i2, LinkedHashSet linkedHashSet, JsonObject jsonObject) {
        File[] listFiles;
        if (i2 <= maxDepth(jsonObject) && (listFiles = file.listFiles()) != null) {
            Arrays.sort(listFiles, BY_NAME);
            for (File file2 : listFiles) {
                if (file2.isDirectory()) {
                    collectExtensions(file2, i2 + 1, linkedHashSet, jsonObject);
                } else if (file2.isFile()) {
                    String str = parseName(file2.getName()).ext;
                    if (!TextUtils.isEmpty(str) && Arrays.asList(EXTENSIONS).contains(str)) {
                        linkedHashSet.add(str);
                    }
                }
            }
        }
    }

    public static ArrayList splitUserText(String str) {
        ArrayList arrayList = new ArrayList();
        if (!TextUtils.isEmpty(str)) {
            for (String str2 : str.replace((char) 65292, ',').replace((char) 65307, ',').replace((char) 12289, ',').split("[,\\n\\r|]")) {
                String replace = str2.trim().replace("\"", "").replace("'", "");
                if (!TextUtils.isEmpty(replace) && !arrayList.contains(replace)) {
                    arrayList.add(replace);
                }
            }
        }
        return arrayList;
    }

    public static int gbkCompare(String str, String str2) {
        try {
            Locale locale = Locale.US;
            byte[] bytes = str.toLowerCase(locale).getBytes(Charset.forName("GBK"));
            byte[] bytes2 = str2.toLowerCase(locale).getBytes(Charset.forName("GBK"));
            int min = Math.min(bytes.length, bytes2.length);
            for (int i2 = 0; i2 < min; i2++) {
                int i3 = (bytes[i2] & 255) - (bytes2[i2] & 255);
                if (i3 != 0) {
                    return i3;
                }
            }
            return bytes.length - bytes2.length;
        } catch (Throwable unused) {
            return str.compareToIgnoreCase(str2);
        }
    }

    public static String getString(JsonObject jsonObject, String str) {
        JsonElement jsonElement = jsonObject.get(str);
        return (jsonElement == null || !jsonElement.isJsonPrimitive()) ? "" : jsonElement.getAsString();
    }

    public static boolean containsAny(String str, List list) {
        if (str == null) {
            return false;
        }
        Iterator it = list.iterator();
        while (it.hasNext()) {
            String str2 = (String) it.next();
            if (!TextUtils.isEmpty(str2) && str.contains(str2)) {
                return true;
            }
        }
        return false;
    }

    public static JsonArray toJsonArray(ArrayList arrayList) {
        JsonArray jsonArray = new JsonArray();
        Iterator it = arrayList.iterator();
        while (it.hasNext()) {
            jsonArray.add((String) it.next());
        }
        return jsonArray;
    }

    public static File groupDirOf(File file, JsonObject jsonObject) {
        Iterator it = enabledDirs(jsonObject).iterator();
        while (it.hasNext()) {
            if (!TextUtils.isEmpty(relativePath(new File((String) it.next()), file))) {
                File parentFile = file.getParentFile();
                if (parentFile == null || !parentFile.isDirectory()) {
                    return null;
                }
                return parentFile;
            }
        }
        return null;
    }

    public static JsonArray toJsonArray2(String[] strArr) {
        JsonArray jsonArray = new JsonArray();
        for (String str : strArr) {
            jsonArray.add(str);
        }
        return jsonArray;
    }

    public static void scanFile(File file, int depth, boolean flagXbpq, boolean flagXyq, boolean flagDrpy, boolean flagLive, ScanCtx ctx, JsonObject cfg, String rootPath) {
        if (depth > maxDepth(cfg)) return;
        if (ctx.d) return;
        String dirName = file.getName();
        String dirDisplay = dirDisplayName(dirName);
        if (!TextUtils.isEmpty(dirDisplay)) {
            ((LinkedHashSet) ctx.i).add(dirDisplay);
        }
        boolean xbpq = flagXbpq || containsAny(dirName, Arrays.asList(TAG_XBPQ));
        boolean xyq = flagXyq || containsAny(dirName, Arrays.asList(TAG_XYQ));
        boolean drpy = flagDrpy || containsAny(dirName, Arrays.asList(TAG_DRPY));
        boolean live = flagLive || "直播文件".equals(dirName);
        File[] children = file.listFiles();
        if (children == null) {
            ctx.note(dirName, "无法读取目录（缺少存储权限？）");
            return;
        }
        Arrays.sort(children, BY_NAME);
        ArrayList blockedDirs = stringList(cfg, "blocked_dirs");
        ArrayList excludedFiles = stringList(cfg, "excluded_files");
        ArrayList scanExtensions = stringList(cfg, "scan_extensions");
        LinkedHashSet extSet = new LinkedHashSet();
        if (scanExtensions.isEmpty()) {
            for (int i = 0; i < EXTENSIONS.length; i++) {
                extSet.add(EXTENSIONS[i]);
            }
        } else {
            for (Object rawObj : scanExtensions) {
                String raw = (String) rawObj;
                if (TextUtils.isEmpty(raw)) continue;
                String t = raw.trim().toLowerCase(Locale.US);
                if ("xbpq".equals(t) || "xyq".equals(t)) {
                    extSet.add("json");
                } else if ("wv.js".equals(t)) {
                    extSet.add("js");
                } else if (Arrays.asList(EXTENSIONS).contains(t)) {
                    extSet.add(t);
                }
            }
            if (extSet.isEmpty()) {
                for (int i = 0; i < EXTENSIONS.length; i++) {
                    extSet.add(EXTENSIONS[i]);
                }
            }
        }
        String mode = "file".equals(getString(cfg, "scan_mode")) ? "file" : "source";
        boolean decodeBase64 = getBool(cfg, "decode_base64", true);
        for (int index = 0; index < children.length; index++) {
            if (ctx.d) return;
            File child = children[index];
            String childName = child.getName();
            if (blockedDirs.contains(childName)) continue;
            if (child.isDirectory()) {
                scanFile(child, depth + 1, xbpq, xyq, drpy, live, ctx, cfg, rootPath);
                continue;
            }
            if (!child.isFile()) continue;
            if (excludedFiles.contains(childName)) continue;
            if (isSelfFile(child, cfg)) continue;
            String canon = canonPath(child.getAbsolutePath());
            if (!TextUtils.isEmpty(canon) && !((LinkedHashSet) ctx.g).add(canon)) continue;
            if (ctx.a >= 20000) {
                ctx.d = true;
                return;
            }
            if (((ArrayList) ctx.e).size() >= 4000) {
                ctx.d = true;
                return;
            }
            ctx.a++;
            NameInfo parsed = parseName(childName);
            String extI = (String) parsed.ext;
            if (!TextUtils.isEmpty(extI)) {
                ((LinkedHashSet) ctx.h).add(extI);
            }
            boolean accept;
            if (TextUtils.isEmpty(extI)) {
                accept = false;
            } else if ("source".equals(mode)) {
                if ("json".equals(extI)) {
                    if (!xbpq && !xyq) {
                        accept = false;
                    } else if (!extSet.contains("json")) {
                        accept = false;
                    } else {
                        accept = true;
                    }
                } else if (Arrays.asList("py", "js", "php", "html").contains(extI)) {
                    accept = extSet.contains(extI);
                } else {
                    accept = false;
                }
            } else {
                if ("json".equals(extI)) {
                    if (xbpq || xyq) {
                        accept = false;
                    } else if (!extSet.contains("json")) {
                        accept = false;
                    } else {
                        accept = true;
                    }
                } else if (Arrays.asList("txt", "m3u", "db", "zip", "pkg").contains(extI)) {
                    if (xbpq || xyq || drpy) {
                        accept = false;
                    } else {
                        accept = extSet.contains(extI);
                    }
                } else {
                    accept = false;
                }
            }
            if (!accept) continue;
            if ("json".equals((String) parsed.rawExt) && (xbpq || xyq) && decodeBase64) {
                try {
                    StringBuilder sb = new StringBuilder();
                    FileInputStream in = new FileInputStream(child);
                    try {
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = in.read(buf)) > 0) {
                            sb.append(new String(buf, 0, read, StandardCharsets.UTF_8));
                        }
                    } catch (Throwable th) {
                        try {
                            in.close();
                        } catch (Throwable sup) {
                            th.addSuppressed(sup);
                        }
                        throw th;
                    }
                    in.close();
                    String text = sb.toString().trim();
                    try {
                        JsonParser.parseString(text);
                    } catch (Throwable parseFail) {
                        byte[] decoded = Base64.decode(text, 0);
                        String decodedText = new String(decoded, StandardCharsets.UTF_8);
                        JsonParser.parseString(decodedText);
                        FileOutputStream out = new FileOutputStream(child, false);
                        try {
                            out.write(decodedText.getBytes(StandardCharsets.UTF_8));
                        } catch (Throwable th) {
                            try {
                                out.close();
                            } catch (Throwable sup) {
                                th.addSuppressed(sup);
                            }
                            throw th;
                        }
                        out.close();
                        DebugLogStore.add("LocalSource", "Base64 规则已解码：" + child.getName());
                    }
                } catch (Throwable th) {
                    DebugLogStore.add("LocalSource", "Base64 解码跳过：" + child.getName() + "（" + th + "）");
                }
            }
            ctx.b++;
            String rel = relativize(child, rootPath);
            String base = (String) parsed.base;
            String cleanBase = base == null ? "" : base.replaceAll("\\[[^\\]]*\\]", "");
            if (TextUtils.isEmpty(cleanBase)) cleanBase = base;
            if (live) {
                StringBuilder sb = new StringBuilder();
                sb.append(cleanBase);
                if (TextUtils.isEmpty(dirDisplay)) {
                    sb.append("");
                } else {
                    sb.append("[" + stripBrackets(dirDisplay) + "]");
                }
                String liveName = sb.toString();
                JsonObject liveObj = new JsonObject();
                liveObj.addProperty("name", liveName);
                liveObj.addProperty("type", Integer.valueOf(0));
                liveObj.addProperty("url", rel);
                liveObj.addProperty("playerType", Integer.valueOf(2));
                liveObj.addProperty("epg", "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}");
                liveObj.addProperty("logo", "https://11.112114.xyz/logo/" + liveName + ".png");
                liveObj.addProperty("ua", "");
                ((ArrayList) ctx.f).add(liveObj);
                if (((ArrayList) ctx.f).size() >= 2000) {
                    ctx.d = true;
                }
                continue;
            }
            JsonObject site;
            if (xbpq && "json".equals((String) parsed.rawExt)) {
                String name = displayName(cleanBase, dirDisplay, extI);
                site = newSite(name, "csp_XBPQ", rel, false, 1);
            } else if (xyq && "json".equals((String) parsed.rawExt)) {
                String name = displayName(cleanBase, dirDisplay, extI);
                site = newSite(name, "csp_XYQHiker", rel, false, 1);
            } else if (drpy && "js".equals((String) parsed.rawExt)) {
                String name = displayName(cleanBase, dirDisplay, extI);
                String api = new File(rootPath, "lib/lib/drpy2-fast.min.js").exists() ? "./lib/lib/drpy2-fast.min.js" : "./lib/drpy2.min.js";
                site = newSite(name, api, rel, false, 1);
                site.addProperty("order_num", Integer.valueOf(0));
            } else if ("php".equals((String) parsed.rawExt)) {
                String api = rel;
                if (api.startsWith("./")) api = api.substring(2);
                String name = displayName(cleanBase, dirDisplay, extI);
                site = new JsonObject();
                site.addProperty("key", name);
                site.addProperty("name", name);
                site.addProperty("type", Integer.valueOf(4));
                site.addProperty("api", "http://0.0.0.0:8901/" + api);
                site.addProperty("searchable", Integer.valueOf(1));
                site.addProperty("quickSearch", Integer.valueOf(1));
                site.addProperty("filterable", Integer.valueOf(1));
                site.addProperty("changeable", Integer.valueOf(1));
            } else if ("wv.js".equals((String) parsed.rawExt)) {
                String name = displayName(cleanBase, dirDisplay, extI);
                site = newSite(name, "csp_WvSpider", rel, true, 0);
                site.addProperty("jar", "./jar/WvSpider.jar");
                site.addProperty("switchable", Integer.valueOf(1));
            } else if ("py".equals((String) parsed.rawExt)) {
                String name = displayName(cleanBase, dirDisplay, extI);
                site = newSite(name, rel, null, true, 0);
            } else if ("js".equals((String) parsed.rawExt)) {
                String name = displayName(cleanBase, dirDisplay, extI);
                site = newSite(name, rel, null, true, 0);
            } else if ("html".equals((String) parsed.rawExt)) {
                String name = displayName(cleanBase, dirDisplay, extI);
                site = newSite(name, "csp_Nostr", null, true, 0);
                site.addProperty("homePage", rel);
            } else {
                String tag = extI;
                if (!Arrays.asList("txt", "m3u", "json", "db", "zip", "pkg").contains((String) parsed.rawExt)
                        && !Arrays.asList("txt", "m3u", "json", "db", "zip", "pkg").contains((String) parsed.kind)) {
                    if (TextUtils.isEmpty(tag)) tag = "file";
                }
                String name = displayName(cleanBase, dirDisplay, tag);
                site = fileSpiderSite(name, rel);
            }
            site.addProperty("#path", canon);
            site.addProperty("#base", (String) parsed.base);
            site.addProperty("#label", (String) parsed.ext);
            ((ArrayList) ctx.e).add(site);
        }
    }
}
