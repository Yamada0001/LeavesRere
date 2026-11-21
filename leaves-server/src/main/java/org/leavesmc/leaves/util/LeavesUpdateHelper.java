package org.leavesmc.leaves.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.papermc.paper.ServerBuildInfo;
import net.minecraft.util.Util;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.LeavesLogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

public class LeavesUpdateHelper {

    public static final String DOWNLOAD_REPOSITORY = "https://github.com/Yamada0001/LeavesRere";
    private static final String RELEASES_API = "https://api.github.com/repos/Yamada0001/LeavesRere/releases";
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_RELEASE_PAGES = 10;
    private static final int RELEASES_PER_PAGE = 100;
    private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private static final long MAX_JAR_BYTES = 1024L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern GIT_SHA = Pattern.compile("[0-9a-fA-F]{7,40}");
    private static final Pattern MC_VERSION = Pattern.compile("[0-9][0-9A-Za-z._+-]*");
    private static final Pattern DOWNLOAD_PATH = Pattern.compile("/Yamada0001/LeavesRere/releases/download/[0-9][0-9A-Za-z._+-]*-[1-9][0-9]*-[0-9a-f]{7,40}/leaves-[0-9][0-9A-Za-z._+-]*\\.jar");
    private static final Pattern RELEASE_ASSET_PATH = Pattern.compile("/github-production-release-asset(?:-[0-9a-f]+)?/[0-9]+/[A-Za-z0-9-]+");

    private final static String autoUpdateDir = "auto_update";
    private final static String corePathFileName = autoUpdateDir + File.separator + "core.path";

    private final static ReentrantLock updateLock = new ReentrantLock();
    private static boolean updateTaskStarted = false;

    private static final ScheduledExecutorService autoUpdateExecutor = Executors.newScheduledThreadPool(1);

    public static void init() {
        File workingDirFile = new File(autoUpdateDir);
        if (!workingDirFile.exists()) {
            if (!workingDirFile.mkdir()) {
                LeavesLogger.LOGGER.warn("Failed to create working directory: {}", autoUpdateDir);
            }
        }

        File corePathFile = new File(corePathFileName);
        if (!corePathFile.exists()) {
            try {
                if (!corePathFile.createNewFile()) {
                    throw new IOException();
                }
            } catch (IOException e) {
                LeavesLogger.LOGGER.error("Failed to create core path file: {}", corePathFileName, e);
            }
        }

        File leavesUpdateDir = new File(autoUpdateDir + File.separator + "leaves");
        if (!leavesUpdateDir.exists()) {
            if (!leavesUpdateDir.mkdir()) {
                LeavesLogger.LOGGER.warn("Failed to create leaves update directory: {}", leavesUpdateDir);
            }
        }

        if (LeavesConfig.mics.autoUpdate.enable) {
            LocalTime currentTime = LocalTime.now();
            long dailyTaskPeriod = 24 * 60 * 60 * 1000;

            for (String time : LeavesConfig.mics.autoUpdate.updateTime) {
                try {
                    LocalTime taskTime = LocalTime.of(Integer.parseInt(time.split(":")[0]), Integer.parseInt(time.split(":")[1]));
                    Duration task = Duration.between(currentTime, taskTime);
                    if (task.isNegative()) {
                        task = task.plusDays(1);
                    }
                    autoUpdateExecutor.scheduleAtFixedRate(LeavesUpdateHelper::tryUpdateLeaves, task.toMillis(), dailyTaskPeriod, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    LeavesLogger.LOGGER.warn("Illegal auto-update time ignored: {}", time);
                }
            }
        }
    }

    public static void tryUpdateLeaves() {
        updateLock.lock();
        try {
            if (!updateTaskStarted) {
                updateTaskStarted = true;
                new Thread(LeavesUpdateHelper::downloadLeaves, "Leaves GitHub updater").start();
            }
        } finally {
            updateLock.unlock();
        }
    }

    private static void downloadLeaves() {
        Path outFile = null;
        try {
            ServerBuildInfo version = ServerBuildInfo.buildInfo();
            if (version.gitCommit().isEmpty() || version.buildNumber().isEmpty()) {
                LeavesLogger.LOGGER.info("IDE, custom build? Can not update!");
                return;
            }

            LeavesLogger.LOGGER.info("Now gitHash: {}", version.gitCommit().get());
            LeavesLogger.LOGGER.info("Trying to get latest build info from GitHub Releases.");
            LeavesBuildInfo buildInfo = getLatestBuildInfo(version.minecraftVersionId(), version.gitCommit().get(), version.buildNumber().getAsInt());
            if (buildInfo == null) {
                LeavesLogger.LOGGER.info("No newer compatible GitHub release with a verifiable checksum is available.");
                return;
            }

            LeavesLogger.LOGGER.info("Got build info, trying to download {}", buildInfo.fileName);
            outFile = Path.of(autoUpdateDir, "leaves", buildInfo.fileName + ".cache");
            Files.deleteIfExists(outFile);
            downloadAsset(buildInfo, outFile, LeavesUpdateHelper::openConnection);

            // Keep the launcher protocol: verified jar in auto_update/leaves and its relative path in core.path.
            Path nowServerCore = Path.of(autoUpdateDir, "leaves", buildInfo.fileName);
            Path backupServerCore = Path.of(autoUpdateDir, "leaves", buildInfo.fileName + ".old");
            Util.safeReplaceFile(nowServerCore, outFile, backupServerCore);
            if (!isFileValid(nowServerCore, buildInfo.sha256)) {
                throw new IOException("Failed to install the verified release asset");
            }
            try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(corePathFileName))) {
                bufferedWriter.write(autoUpdateDir + File.separator + "leaves" + File.separator + buildInfo.fileName);
            }
            LeavesLogger.LOGGER.info("Leaves update completed, please restart your server.");
        } catch (Exception e) {
            LeavesLogger.LOGGER.error("Leaves update failed", e);
        } finally {
            if (outFile != null) {
                try {
                    Files.deleteIfExists(outFile);
                } catch (IOException e) {
                    LeavesLogger.LOGGER.warn("Failed to remove incomplete update download", e);
                }
            }
            updateLock.lock();
            try {
                updateTaskStarted = false;
            } finally {
                updateLock.unlock();
            }
        }
    }

    static boolean isFileValid(Path file, String hash) {
        if (hash == null || !SHA256.matcher(hash).matches()) {
            return false;
        }
        try (InputStream inputStream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (int numRead; (numRead = inputStream.read(buffer)) != -1; ) {
                sha256.update(buffer, 0, numRead);
            }
            return MessageDigest.isEqual(sha256.digest(), HexFormat.of().parseHex(hash));
        } catch (Exception e) {
            LeavesLogger.LOGGER.warn("Fail to validate file {}", file, e);
            return false;
        }
    }

    private static LeavesBuildInfo getLatestBuildInfo(String mcVersion, String gitHash, int currentBuild) throws IOException {
        LeavesBuildInfo latest = null;
        for (int page = 1; page <= MAX_RELEASE_PAGES; page++) {
            URI uri = URI.create(RELEASES_API + "?per_page=" + RELEASES_PER_PAGE + "&page=" + page);
            HttpURLConnection connection = openConnection(uri);
            try {
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                // Repository moves and redirects must not silently change the approved source.
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("GitHub Releases API returned HTTP " + connection.getResponseCode());
                }
                JsonArray releases;
                try (InputStream input = connection.getInputStream()) {
                    byte[] json = input.readNBytes(MAX_METADATA_BYTES + 1);
                    if (json.length > MAX_METADATA_BYTES) {
                        throw new IOException("GitHub Releases response is too large");
                    }
                    releases = new Gson().fromJson(new String(json, StandardCharsets.UTF_8), JsonArray.class);
                    if (releases == null || releases.size() > RELEASES_PER_PAGE) {
                        throw new IOException("Invalid GitHub Releases response");
                    }
                }
                latest = newerRelease(latest, selectRelease(releases, mcVersion, gitHash, currentBuild, LeavesConfig.mics.autoUpdate.allowExperimental));
                if (releases.size() < RELEASES_PER_PAGE) {
                    return latest;
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("GitHub release listing exceeded the safe pagination limit");
    }

    // Pure selection/verification: no network calls, also used by the offline regression tests.
    static LeavesBuildInfo selectRelease(JsonArray releases, String mcVersion, String gitHash, int currentBuild, boolean allowExperimental) {
        if (!MC_VERSION.matcher(mcVersion).matches() || !GIT_SHA.matcher(gitHash).matches() || currentBuild < 0) {
            return null;
        }
        Pattern tagPattern = Pattern.compile(Pattern.quote(mcVersion) + "-([1-9][0-9]*)-([0-9a-f]{7,40})");
        LeavesBuildInfo latest = null;
        for (JsonElement element : releases) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject release = element.getAsJsonObject();
            if (!isBoolean(release, "draft", false) || !(isBoolean(release, "prerelease", false) || allowExperimental && isBoolean(release, "prerelease", true))) {
                continue;
            }
            String tag = string(release, "tag_name");
            if (tag == null) {
                continue;
            }
            Matcher matcher = tagPattern.matcher(tag);
            if (!matcher.matches()) {
                continue;
            }
            int build;
            try {
                build = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String sha = matcher.group(2);
            String currentSha = gitHash.toLowerCase(Locale.ROOT);
            if (build <= currentBuild || sha.startsWith(currentSha) || currentSha.startsWith(sha)) {
                continue;
            }
            LeavesBuildInfo candidate = releaseAsset(release, mcVersion, tag, sha, build);
            latest = newerRelease(latest, candidate);
        }
        return latest;
    }

    private static LeavesBuildInfo newerRelease(LeavesBuildInfo first, LeavesBuildInfo second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        if (first.build == second.build && !first.equals(second)) {
            throw new IllegalArgumentException("Conflicting GitHub releases have the same build number");
        }
        return second.build > first.build ? second : first;
    }

    private static LeavesBuildInfo releaseAsset(JsonObject release, String mcVersion, String tag, String sha, int build) {
        JsonElement assets = release.get("assets");
        if (assets == null || !assets.isJsonArray()) {
            return null;
        }
        String expectedName = "leaves-" + mcVersion + "-" + sha + ".jar";
        String legacyName = "leaves-" + mcVersion + ".jar";
        JsonObject selected = null;
        JsonObject legacy = null;
        int expectedCount = 0;
        int legacyCount = 0;
        int jarCount = 0;
        for (JsonElement element : assets.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            String name = string(asset, "name");
            if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                jarCount++;
            }
            if (expectedName.equals(name)) {
                selected = asset;
                expectedCount++;
            } else if (legacyName.equals(name)) {
                legacy = asset;
                legacyCount++;
            }
        }
        // A legacy filename does not identify the commit: accept only one jar, bound to its exact notes checksum.
        boolean legacyAsset = expectedCount == 0 && legacyCount == 1 && jarCount == 1;
        if (legacyAsset) {
            selected = legacy;
            expectedName = legacyName;
        } else if (expectedCount != 1) {
            return null;
        }
        if (!"uploaded".equals(string(selected, "state"))) {
            return null;
        }
        String sha256 = releaseChecksum(selected, string(release, "body"), expectedName, legacyAsset);
        if (sha256 == null) {
            return null;
        }
        long size;
        try {
            JsonElement value = selected.get("size");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            size = value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
        URI expectedUrl = URI.create(DOWNLOAD_REPOSITORY + "/releases/download/" + tag + "/" + expectedName);
        if (size <= 0 || size > MAX_JAR_BYTES || !expectedUrl.toString().equals(string(selected, "browser_download_url"))) {
            return null;
        }
        return new LeavesBuildInfo(build, expectedName, sha256, expectedUrl, size);
    }

    private static String releaseChecksum(JsonObject asset, String body, String fileName, boolean legacyAsset) {
        String digest = null;
        JsonElement digestValue = asset.get("digest");
        if (digestValue != null && !digestValue.isJsonNull()) {
            String value = string(asset, "digest");
            if (value == null || !value.startsWith("sha256:") || !SHA256.matcher(value.substring(7)).matches()) {
                return null;
            }
            digest = value.substring(7).toLowerCase(Locale.ROOT);
        }
        String notesHash = null;
        if (body != null) {
            // Match the exact sha256sum line emitted by build.yml, never a hash for a different asset.
            Matcher checksum = Pattern.compile("(?m)^([^\\r\\n]+)  " + Pattern.quote(fileName) + "\\r?$").matcher(body);
            while (checksum.find()) {
                String hash = checksum.group(1);
                if (!SHA256.matcher(hash).matches()) {
                    return null;
                }
                hash = hash.toLowerCase(Locale.ROOT);
                if (notesHash != null && !notesHash.equals(hash)) {
                    return null;
                }
                notesHash = hash;
            }
        }
        if (legacyAsset && notesHash == null || digest != null && notesHash != null && !digest.equals(notesHash)) {
            return null;
        }
        return digest != null ? digest : notesHash;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static boolean isBoolean(JsonObject object, String key, boolean expected) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean() == expected;
    }

    private static HttpURLConnection openConnection(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        configureConnection(connection);
        return connection;
    }

    private static void configureConnection(HttpURLConnection connection) {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", "LeavesRere-Updater");
    }

    static boolean isAllowedDownloadUri(URI uri, URI expectedUrl) {
        if (!"https".equals(uri.getScheme()) || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            return false;
        }
        if ("github.com".equals(uri.getHost())) {
            return uri.equals(expectedUrl) && uri.getRawQuery() == null && DOWNLOAD_PATH.matcher(uri.getRawPath()).matches();
        }
        return ("release-assets.githubusercontent.com".equals(uri.getHost()) || "objects.githubusercontent.com".equals(uri.getHost()))
            && RELEASE_ASSET_PATH.matcher(uri.getRawPath()).matches();
    }

    static void downloadAsset(LeavesBuildInfo buildInfo, Path destination, ConnectionFactory connections) throws IOException {
        URI uri = buildInfo.url;
        if (!uri.toString().startsWith(DOWNLOAD_REPOSITORY + "/releases/download/") || !isAllowedDownloadUri(uri, buildInfo.url)) {
            throw new IOException("Invalid GitHub release asset URL");
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!isAllowedDownloadUri(uri, buildInfo.url)) {
                throw new IOException("Untrusted GitHub release download redirect");
            }
            HttpURLConnection connection = connections.open(uri);
            try {
                configureConnection(connection);
                connection.setRequestProperty("Accept", "application/octet-stream");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == MAX_REDIRECTS) {
                        throw new IOException("Invalid or excessive GitHub release download redirects");
                    }
                    try {
                        uri = uri.resolve(URI.create(location));
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Invalid GitHub release download redirect", e);
                    }
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("GitHub release asset returned HTTP " + status);
                }
                long contentLength = connection.getContentLengthLong();
                if (contentLength >= 0 && contentLength != buildInfo.size) {
                    throw new IOException("GitHub release asset size differs from its metadata");
                }
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(destination, CREATE_NEW, WRITE)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    for (int length; (length = input.read(buffer)) != -1; ) {
                        total += length;
                        if (total > buildInfo.size || System.nanoTime() > deadline) {
                            throw new IOException("GitHub release download exceeded its size or time limit");
                        }
                        output.write(buffer, 0, length);
                    }
                    if (total != buildInfo.size) {
                        throw new IOException("Incomplete GitHub release asset download");
                    }
                }
                if (!isFileValid(destination, buildInfo.sha256)) {
                    throw new IOException("SHA-256 check failed for downloaded release asset");
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many GitHub release download redirects");
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }

    record LeavesBuildInfo(int build, String fileName, String sha256, URI url, long size) {
    }
}
