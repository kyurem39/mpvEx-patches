package app.kyurem39.extension;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates external subtitle files into the language chosen in the patch settings.
 *
 * <p>The app loads subtitles through two paths: the automatic loader in {@code SubtitleOps} and
 * the manual picker in {@code PlayerViewModel}. Both paths are hooked by the patch and end up here.
 * A translated copy is written to the app cache and added as a separate mpv track, so the original
 * subtitle remains available.
 *
 * <p>This class is written in Java because the app's Kotlin runtime was shrunk by R8 and must not be
 * used by extension code. It only uses framework APIs, plus {@code org.json}, which is part of
 * Android.
 */
public final class MorpheSubtitles {
    private static final String TAG = "MorpheSubtitles";
    private static final String ENDPOINT =
        "https://translate.googleapis.com/translate_a/single";
    private static final int MAX_BATCH_CHARS = 3000;
    private static final String MARKER = "@@@";

    private static final String[] CODES = {
        "vi", "en", "ja", "ko", "zh-CN", "zh-TW", "th", "id", "ms", "fil",
        "hi", "es", "fr", "de", "pt", "ru", "ar", "it", "tr",
    };

    private static final String[] ENGLISH_NAMES = {
        "Vietnamese", "English", "Japanese", "Korean",
        "Chinese (Simplified)", "Chinese (Traditional)", "Thai", "Indonesian",
        "Malay", "Filipino", "Hindi", "Spanish", "French", "German",
        "Portuguese", "Russian", "Arabic", "Italian", "Turkish",
    };

    private static final String[] VIETNAMESE_NAMES = {
        "Tiếng Việt", "Tiếng Anh", "Tiếng Nhật", "Tiếng Hàn",
        "Tiếng Trung (Giản thể)", "Tiếng Trung (Phồn thể)", "Tiếng Thái",
        "Tiếng Indonesia", "Tiếng Mã Lai", "Tiếng Philippines", "Tiếng Hindi",
        "Tiếng Tây Ban Nha", "Tiếng Pháp", "Tiếng Đức", "Tiếng Bồ Đào Nha",
        "Tiếng Nga", "Tiếng Ả Rập", "Tiếng Ý", "Tiếng Thổ Nhĩ Kỳ",
    };

    private static final Pattern MICRO_DVD_LINE =
        Pattern.compile("^(\\{\\d+\\}\\{\\d+\\})(.*)$");
    private static final Pattern MARKER_SPLIT = Pattern.compile("\\s*@@@\\s*");

    private static final Set<String> RUNNING =
        Collections.synchronizedSet(new HashSet<String>());

    private MorpheSubtitles() {}

    public static String[] languageCodes() {
        return CODES.clone();
    }

    public static String[] languageNames(boolean vietnamese) {
        return (vietnamese ? VIETNAMESE_NAMES : ENGLISH_NAMES).clone();
    }

    public static String displayName(String code, boolean vietnamese) {
        if (code != null) {
            for (int index = 0; index < CODES.length; index++) {
                if (CODES[index].equalsIgnoreCase(code)) {
                    return (vietnamese ? VIETNAMESE_NAMES : ENGLISH_NAMES)[index];
                }
            }
        }
        return code == null ? "" : code;
    }

    /** Called before the app's automatic local-subtitle scan. */
    public static void onAutoload(String videoFilePath, String videoFileName) {
        try {
            Context context = appContext();
            if (context == null || !MorphePrefs.isSubtitleTranslationEnabled(context)) return;
            if (videoFilePath == null || videoFileName == null) return;
            if (videoFilePath.startsWith("content://") || videoFilePath.startsWith("fd://")) {
                return;
            }

            File video = new File(videoFilePath);
            File directory = video.getParentFile();
            if (directory == null) return;

            String baseName = videoFileName;
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) baseName = baseName.substring(0, dot);
            String base = baseName.toLowerCase(Locale.ROOT);

            File[] files = directory.listFiles();
            if (files == null) return;

            Activity owner = MorpheHook.currentActivity();

            for (File file : files) {
                if (!file.isFile() || !isTranslatable(file.getName())) continue;
                String name = file.getName();
                int nameDot = name.lastIndexOf('.');
                String stem = nameDot > 0 ? name.substring(0, nameDot) : name;
                if (!stem.toLowerCase(Locale.ROOT).startsWith(base)) continue;

                translateFile(context, file, name, owner);
                return;
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Could not start automatic subtitle translation", failure);
        }
    }

    /** Called before the app adds a subtitle chosen by the user. */
    public static void onSubtitleUri(Uri uri) {
        try {
            Context context = appContext();
            if (context == null || uri == null) return;
            if (!MorphePrefs.isSubtitleTranslationEnabled(context)) return;

            String scheme = uri.getScheme();
            Activity owner = MorpheHook.currentActivity();
            if (scheme == null || "file".equalsIgnoreCase(scheme)) {
                String path = uri.getPath();
                if (path == null) return;
                File file = new File(path);
                if (isTranslatable(file.getName())) {
                    translateFile(context, file, file.getName(), owner);
                }
            } else if ("content".equalsIgnoreCase(scheme)) {
                String name = queryDisplayName(context, uri);
                if (name == null || !isTranslatable(name)) return;
                translateContent(context, uri, name, owner);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Could not start subtitle translation", failure);
        }
    }

    private static void translateFile(
        final Context context,
        final File source,
        final String displayName,
        final Activity owner
    ) {
        if (!source.isFile() || source.length() == 0) return;

        final String target = MorphePrefs.subtitleTargetLanguage(context);
        final String key = source.getAbsolutePath() + "|" + target;
        final File cache = cacheFile(context, key, displayName, target);
        if (cache == null) return;

        if (cache.isFile() && cache.length() > 0 && cache.lastModified() >= source.lastModified()) {
            addTrackLater(context, cache, displayName, target, owner);
            return;
        }

        if (!RUNNING.add(key)) return;

        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        String text = readText(source);
                        Translation translation =
                            translateText(text, target, extensionOf(displayName));
                        if (translation == null) return;
                        if (sameLanguage(translation.detected, target) || !translation.changed) {
                            Log.i(TAG, "Subtitle already in " + target + ": " + source);
                            return;
                        }
                        writeText(cache, translation.text);
                        addTrack(context, cache, displayName, target, owner);
                    } catch (Throwable failure) {
                        Log.w(TAG, "Could not translate " + source, failure);
                    } finally {
                        RUNNING.remove(key);
                    }
                }
            },
            "MorpheSubtitleTranslation"
        ).start();
    }

    private static void translateContent(
        final Context context,
        final Uri uri,
        final String displayName,
        final Activity owner
    ) {
        final String target = MorphePrefs.subtitleTargetLanguage(context);
        final String key = uri.toString() + "|" + target;
        final File cache = cacheFile(context, key, displayName, target);
        if (cache == null) return;

        if (cache.isFile() && cache.length() > 0) {
            addTrackLater(context, cache, displayName, target, owner);
            return;
        }

        if (!RUNNING.add(key)) return;

        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        String text = readText(context, uri);
                        Translation translation =
                            translateText(text, target, extensionOf(displayName));
                        if (translation == null) return;
                        if (sameLanguage(translation.detected, target) || !translation.changed) {
                            Log.i(TAG, "Subtitle already in " + target + ": " + uri);
                            return;
                        }
                        writeText(cache, translation.text);
                        addTrack(context, cache, displayName, target, owner);
                    } catch (Throwable failure) {
                        Log.w(TAG, "Could not translate " + uri, failure);
                    } finally {
                        RUNNING.remove(key);
                    }
                }
            },
            "MorpheSubtitleTranslation"
        ).start();
    }

    /**
     * Adds the translated track on a background thread.
     *
     * <p>The track is added synchronously from the hook when the translation is already cached, and
     * the hook runs inside the app's own subtitle-loading coroutine. Selecting the track takes a
     * moment, and that must not delay the app's own subtitle commands.
     */
    private static void addTrackLater(
        final Context context,
        final File file,
        final String displayName,
        final String target,
        final Activity owner
    ) {
        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    addTrack(context, file, displayName, target, owner);
                }
            },
            "MorpheSubtitleTrack"
        ).start();
    }

    private static void addTrack(
        Context context,
        File file,
        String sourceName,
        String target,
        Activity owner
    ) {
        if (file == null || !file.isFile() || file.length() == 0) return;

        // Translation can take a while. mpv must not be called once its player has been closed:
        // the native handle is gone by then.
        if (playerGone(owner)) {
            Log.i(TAG, "Player closed before " + sourceName + " could be translated");
            return;
        }

        String name = sourceName == null || sourceName.isEmpty() ? "Subtitle" : sourceName;
        String title = name + " [" + displayName(target, isVietnameseUi(context)) + "]";

        int translated = -1;
        try {
            Object instance = mpvInstance();
            Method command = mpvMethod("command", String[].class);
            int before = trackCount();
            command.invoke(
                instance,
                (Object) new String[] {"sub-add", file.getAbsolutePath(), "select", title}
            );
            translated = trackIdWithTitle(before, title);
            Log.i(
                TAG,
                "Added translated subtitle: " + file + " (" + target + "), track " + translated
            );
        } catch (Throwable failure) {
            Log.w(TAG, "Could not add translated subtitle track", failure);
            return;
        }

        if (translated <= 0) logTracks();

        // The app adds its own copy of the subtitle too, and that copy is selected when it is
        // added, which would undo the selection above. The app can do that seconds after this
        // track was added, so keep the translated track selected for a while. Only the selection
        // is written, and only when it actually changed: writing it on every pass would keep mpv
        // busy and delay the app's own subtitle commands.
        long deadline = System.currentTimeMillis() + 5000;
        int track = translated;
        while (System.currentTimeMillis() < deadline) {
            if (playerGone(owner)) return;
            if (track <= 0) track = findTrackId(title);
            selectId(track);
            sleep(150);
        }
    }

    /** Lists the subtitle tracks in logcat. Used when the translated track cannot be found. */
    private static void logTracks() {
        try {
            Object instance = mpvInstance();
            Method getString = mpvMethod("getPropertyString", String.class);
            Method getInt = mpvMethod("getPropertyInt", String.class);

            int count = trackCount();
            for (int index = 0; index < count; index++) {
                Object type = getString.invoke(instance, "track-list/" + index + "/type");
                Object id = getInt.invoke(instance, "track-list/" + index + "/id");
                Object title = getString.invoke(instance, "track-list/" + index + "/title");
                Log.d(TAG, "Track " + id + " (" + type + "): " + title);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Could not read subtitle track list", failure);
        }
    }

    /** Id of the subtitle track carrying {@code title}, once it has appeared, or -1. */
    private static int trackIdWithTitle(int before, String title) {
        if (before < 0) return -1;

        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            int count = trackCount();
            if (count > before) {
                int id = findTrackId(title);
                if (id > 0) return id;
            }
            sleep(50);
        }
        return -1;
    }

    /** Id of the subtitle track whose title matches, or -1. */
    private static int findTrackId(String title) {
        try {
            Object instance = mpvInstance();
            Method getString = mpvMethod("getPropertyString", String.class);
            Method getInt = mpvMethod("getPropertyInt", String.class);

            int count = trackCount();
            for (int index = 0; index < count; index++) {
                Object type = getString.invoke(instance, "track-list/" + index + "/type");
                if (!"sub".equals(type)) continue;
                Object trackTitle = getString.invoke(instance, "track-list/" + index + "/title");
                if (!title.equals(trackTitle)) continue;

                Object id = getInt.invoke(instance, "track-list/" + index + "/id");
                if (id instanceof Integer) return (Integer) id;
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Could not read subtitle track list", failure);
        }
        return -1;
    }

    /** Selects a subtitle track by id unless it is selected already. */
    private static void selectId(int id) {
        if (id <= 0) return;

        String selected = selectedSid();
        if (selected != null && selected.equals(String.valueOf(id))) return;

        try {
            mpvMethod("setPropertyInt", String.class, int.class).invoke(mpvInstance(), "sid", id);
            Log.d(TAG, "Selected translated subtitle track " + id);
        } catch (Throwable failure) {
            Log.w(TAG, "Could not select translated subtitle track " + id, failure);
        }
    }

    /**
     * Value of mpv's {@code sid} property, or {@code null} when it cannot be read.
     *
     * <p>{@code sid} has no integer representation, so it is read as a string: a track id, or
     * {@code no} when subtitles are off.
     */
    private static String selectedSid() {
        try {
            Object value = mpvMethod("getPropertyString", String.class).invoke(
                mpvInstance(),
                "sid"
            );
            return value instanceof String ? ((String) value).trim() : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Whether the player this translation belongs to has been left. */
    private static boolean playerGone(Activity owner) {
        if (owner == null) return false;
        if (owner.isFinishing() || owner.isDestroyed()) return true;
        return MorpheHook.currentActivity() != owner;
    }

    /** Number of tracks mpv currently reports, or -1 when mpv is not reachable. */
    private static int trackCount() {
        try {
            Object value = mpvMethod("getPropertyInt", String.class).invoke(
                mpvInstance(),
                "track-list/count"
            );
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable failure) {
            return -1;
        }
    }

    private static Object mpvInstance() throws Exception {
        Class<?> mpv = Class.forName("is.xyz.mpv.MPVLib");
        return mpv.getField("INSTANCE").get(null);
    }

    private static Method mpvMethod(String name, Class<?>... parameters) throws Exception {
        return Class.forName("is.xyz.mpv.MPVLib").getMethod(name, parameters);
    }

    private static Translation translateText(String text, String target, String extension)
        throws IOException {
        if (text == null || text.trim().isEmpty()) return null;
        if ("ass".equals(extension) || "ssa".equals(extension)) {
            return translateAss(text, target);
        }
        if ("vtt".equals(extension)) {
            return translateSrt(text, target);
        }
        if ("sub".equals(extension)) {
            return translateMicroDvd(text, target);
        }
        return translateSrt(text, target);
    }

    private static Translation translateSrt(String source, String target)
        throws IOException {
        String normalized = normalize(source);
        String[] blocks = normalized.split("\n\n+", -1);

        List<Cue> cues = new ArrayList<Cue>();
        List<String> texts = new ArrayList<String>();

        for (String block : blocks) {
            String[] lines = block.split("\n", -1);
            int timing = findTimingLine(lines);
            if (timing < 0 || timing + 1 >= lines.length) {
                cues.add(new Cue(block, null));
                continue;
            }

            String text = join(lines, timing + 1, lines.length);
            if (text.trim().isEmpty()) {
                cues.add(new Cue(block, null));
                continue;
            }

            cues.add(new Cue(block, text));
            texts.add(text);
        }

        if (texts.isEmpty()) return null;

        BatchResult result = translateAll(texts, target);
        StringBuilder out = new StringBuilder();
        int translatedIndex = 0;

        for (int index = 0; index < cues.size(); index++) {
            if (index > 0) out.append("\n\n");

            Cue cue = cues.get(index);
            if (cue.text == null) {
                out.append(cue.block);
                continue;
            }

            String[] lines = cue.block.split("\n", -1);
            int timing = findTimingLine(lines);
            out.append(join(lines, 0, timing + 1));
            out.append('\n');
            out.append(result.texts.get(translatedIndex++));
        }

        out.append('\n');
        return new Translation(out.toString(), result.detected, result.changed);
    }

    private static Translation translateAss(String source, String target) throws IOException {
        String normalized = normalize(source);
        String[] lines = normalized.split("\n", -1);

        boolean events = false;
        int textField = 9;
        List<Integer> dialogueLines = new ArrayList<Integer>();
        List<String> texts = new ArrayList<String>();

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.trim();

            if (trimmed.equalsIgnoreCase("[Events]")) {
                events = true;
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                events = false;
                continue;
            }
            if (!events) continue;

            if (trimmed.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] fields = trimmed.substring(7).split(",");
                for (int field = 0; field < fields.length; field++) {
                    if (fields[field].trim().equalsIgnoreCase("Text")) {
                        textField = field;
                        break;
                    }
                }
            }

            if (!line.regionMatches(true, 0, "Dialogue:", 0, 9)) continue;

            int start = fieldStart(line, textField);
            if (start < 0 || start >= line.length()) continue;

            String text = line.substring(start);
            if (text.trim().isEmpty()) continue;

            dialogueLines.add(index);
            texts.add(text);
        }

        if (texts.isEmpty()) return null;

        BatchResult result = translateAll(texts, target);
        String[] output = lines.clone();
        int translatedIndex = 0;

        for (Integer lineIndex : dialogueLines) {
            String line = output[lineIndex];
            int start = fieldStart(line, textField);
            if (start < 0) continue;

            String original = line.substring(start);
            String leading = leadingWhitespace(original);
            output[lineIndex] = line.substring(0, start)
                + leading
                + result.texts.get(translatedIndex++);
        }

        return new Translation(
            join(output, 0, output.length),
            result.detected,
            result.changed
        );
    }

    private static Translation translateMicroDvd(String source, String target) throws IOException {
        String normalized = normalize(source);
        String[] lines = normalized.split("\n", -1);
        List<String> texts = new ArrayList<String>();
        List<Integer> lineIndexes = new ArrayList<Integer>();

        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = MICRO_DVD_LINE.matcher(lines[index]);
            if (!matcher.matches()) continue;

            String text = matcher.group(2);
            if (text.trim().isEmpty()) continue;

            lineIndexes.add(index);
            texts.add(text.replace("|", "\n"));
        }

        if (texts.isEmpty()) return null;

        BatchResult result = translateAll(texts, target);
        String[] output = lines.clone();
        int translatedIndex = 0;

        for (Integer lineIndex : lineIndexes) {
            Matcher matcher = MICRO_DVD_LINE.matcher(output[lineIndex]);
            if (!matcher.matches()) continue;

            String translated = result.texts.get(translatedIndex++).replace("\n", "|");
            output[lineIndex] = matcher.group(1) + translated;
        }

        return new Translation(
            join(output, 0, output.length),
            result.detected,
            result.changed
        );
    }

    private static BatchResult translateAll(List<String> texts, String target)
        throws IOException {
        List<String> translated = new ArrayList<String>(texts.size());
        String detected = null;
        boolean changed = false;
        int index = 0;

        while (index < texts.size()) {
            int end = index;
            int chars = 0;

            while (end < texts.size()) {
                int length = texts.get(end).length();
                if (end > index && chars + length + MARKER.length() + 2 > MAX_BATCH_CHARS) {
                    break;
                }
                chars += length + MARKER.length() + 2;
                end++;
            }

            List<String> batch = texts.subList(index, end);
            BatchResult batchResult = translateBatch(batch, target);

            if (detected == null && batchResult.detected != null
                && !batchResult.detected.isEmpty()) {
                detected = batchResult.detected;
            }

            for (int item = 0; item < batch.size(); item++) {
                String value = batchResult.texts.get(item);
                translated.add(value);
                if (!value.equals(batch.get(item))) changed = true;
            }

            index = end;
            if (index < texts.size()) sleep(200);
        }

        return new BatchResult(translated, detected, changed);
    }

    private static BatchResult translateBatch(List<String> batch, String target)
        throws IOException {
        if (batch.size() == 1) {
            Response response = request(batch.get(0), target);
            List<String> value = Collections.singletonList(response.text);
            return new BatchResult(value, response.detected, false);
        }

        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < batch.size(); index++) {
            if (index > 0) joined.append('\n').append(MARKER).append('\n');
            joined.append(batch.get(index));
        }

        Response response = request(joined.toString(), target);
        String[] parts = MARKER_SPLIT.split(response.text, -1);
        if (parts.length == batch.size()) {
            List<String> value = new ArrayList<String>(parts.length);
            Collections.addAll(value, parts);
            return new BatchResult(value, response.detected, false);
        }

        // Google merged or dropped a cue. Fall back to one request per cue so the
        // translated file still lines up with the original timings.
        Log.w(TAG, "Batch split mismatch (" + parts.length + " of " + batch.size()
            + "), translating cues individually");
        List<String> value = new ArrayList<String>(batch.size());
        String detected = response.detected;
        for (String item : batch) {
            Response single = request(item, target);
            value.add(single.text);
            if (detected == null || detected.isEmpty()) detected = single.detected;
            sleep(100);
        }
        return new BatchResult(value, detected, false);
    }

    private static Response request(String text, String target) throws IOException {
        String body = "client=gtx&sl=auto&dt=t&tl="
            + URLEncoder.encode(target, "UTF-8")
            + "&q="
            + URLEncoder.encode(text, "UTF-8");

        IOException lastFailure = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                );
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                );

                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }

                int code = connection.getResponseCode();
                if (code == 429 || code >= 500) {
                    lastFailure = new IOException("HTTP " + code);
                    sleep(1000L << attempt);
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);

                String response = readAll(connection.getInputStream());
                if (response.isEmpty()) throw new IOException("Empty translation response");

                JSONArray root = new JSONArray(response);
                JSONArray segments = root.optJSONArray(0);
                StringBuilder translated = new StringBuilder();

                if (segments != null) {
                    for (int index = 0; index < segments.length(); index++) {
                        JSONArray segment = segments.optJSONArray(index);
                        if (segment != null) translated.append(segment.optString(0, ""));
                    }
                }

                if (translated.length() == 0) {
                    throw new IOException("Translation response has no text");
                }

                return new Response(translated.toString(), root.optString(2, ""));
            } catch (IOException failure) {
                lastFailure = failure;
                if (attempt == 2) break;
                sleep(1000L << attempt);
            } catch (Exception failure) {
                lastFailure = new IOException("Could not parse translation response", failure);
                if (attempt == 2) break;
                sleep(1000L << attempt);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        throw lastFailure == null ? new IOException("Translation failed") : lastFailure;
    }

    private static boolean sameLanguage(String detected, String target) {
        if (detected == null || detected.isEmpty() || target == null || target.isEmpty()) {
            return false;
        }

        String first = detected.toLowerCase(Locale.ROOT).replace('_', '-');
        String second = target.toLowerCase(Locale.ROOT).replace('_', '-');

        if (first.equals(second)) return true;

        if (first.startsWith("zh") && second.startsWith("zh")) {
            if (first.equals("zh") || second.equals("zh")) return false;
            boolean firstTraditional = first.contains("tw") || first.contains("hant");
            boolean secondTraditional = second.contains("tw") || second.contains("hant");
            return firstTraditional == secondTraditional;
        }

        return first.startsWith(second + "-") || second.startsWith(first + "-");
    }

    private static String queryDisplayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                uri,
                new String[] {OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
            );
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Throwable ignored) {
            // Fall back to the last path segment.
        } finally {
            if (cursor != null) cursor.close();
        }

        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "subtitle.srt" : last;
    }

    private static File cacheFile(
        Context context,
        String key,
        String sourceName,
        String target
    ) {
        File root = context.getCacheDir();
        if (root == null) root = context.getFilesDir();
        if (root == null) return null;

        File directory = new File(root, "morphe-subtitles");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create subtitle cache directory");
        }

        String extension = extensionOf(sourceName);
        if ("ssa".equals(extension)) extension = "ass";
        if (!isTranslatable(sourceName)) extension = "srt";

        String base = sourceName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.length() > 40) base = base.substring(0, 40);
        if (base.isEmpty()) base = "subtitle";

        return new File(
            directory,
            Integer.toHexString(key.hashCode()) + "-" + base + "." + target + "." + extension
        );
    }

    private static boolean isTranslatable(String fileName) {
        String extension = extensionOf(fileName);
        return "srt".equals(extension)
            || "vtt".equals(extension)
            || "ass".equals(extension)
            || "ssa".equals(extension)
            || "sub".equals(extension);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int findTimingLine(String[] lines) {
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains("-->")) return index;
        }
        return -1;
    }

    private static int fieldStart(String line, int fieldIndex) {
        if (fieldIndex < 0) return -1;
        int commas = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) != ',') continue;
            commas++;
            if (commas == fieldIndex) return index + 1;
        }
        return -1;
    }

    private static String leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return value.substring(0, index);
    }

    private static String join(String[] lines, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int index = from; index < to; index++) {
            if (index > from) out.append('\n');
            out.append(lines[index]);
        }
        return out.toString();
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return decode(readAllBytes(input));
        }
    }

    private static String readText(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Could not open " + uri);
            return decode(readAllBytes(input));
        }
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String readAll(InputStream input) throws IOException {
        return new String(readAllBytes(input), StandardCharsets.UTF_8);
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            return normalize(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
        }
        if (bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xFE) {
            return normalize(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE));
        }
        if (bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFE
            && (bytes[1] & 0xFF) == 0xFF) {
            return normalize(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE));
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return normalize(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException ignored) {
            return normalize(new String(bytes, Charset.forName("windows-1252")));
        }
    }

    private static String normalize(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void writeText(File file, String text) throws IOException {
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }

        File temporary = new File(file.getAbsolutePath() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(temporary),
            StandardCharsets.UTF_8
        )) {
            writer.write(text);
        }

        if (temporary.renameTo(file)) return;

        try (
            InputStream input = new FileInputStream(temporary);
            OutputStream output = new FileOutputStream(file)
        ) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } finally {
            // Best effort; the temporary file is inside the cache.
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private static Context appContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) return (Context) application;
        } catch (Throwable ignored) {
            // Fall through to the tracked activity.
        }

        Activity activity = MorpheHook.currentActivity();
        return activity;
    }

    private static boolean isVietnameseUi(Context context) {
        return "vi".equals(
            context.getResources().getConfiguration().getLocales().get(0).getLanguage()
        );
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Cue {
        final String block;
        final String text;

        Cue(String block, String text) {
            this.block = block;
            this.text = text;
        }
    }

    private static final class Response {
        final String text;
        final String detected;

        Response(String text, String detected) {
            this.text = text;
            this.detected = detected;
        }
    }

    private static final class BatchResult {
        final List<String> texts;
        final String detected;
        final boolean changed;

        BatchResult(List<String> texts, String detected, boolean changed) {
            this.texts = texts;
            this.detected = detected;
            this.changed = changed;
        }
    }

    private static final class Translation {
        final String text;
        final String detected;
        final boolean changed;

        Translation(String text, String detected, boolean changed) {
            this.text = text;
            this.detected = detected;
            this.changed = changed;
        }
    }
}
