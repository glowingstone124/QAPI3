package org.qo.server;

import org.qo.utils.Request;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class AvatarCache {
    public static final String CachePath = "avatars/";
    public static final long MAX_AVATAR_BYTES = 1024L * 1024L;
    private static final long DEFAULT_CACHE_TTL_MILLIS = Duration.ofDays(1).toMillis();
    private static final Pattern MINECRAFT_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern CACHE_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    private static final String SPECIAL_KEY_PREFIX = "special-";
    private static final Request REQUEST = new Request();
    private static final ConcurrentHashMap<String, CompletableFuture<Path>> IN_FLIGHT = new ConcurrentHashMap<>();

    private static volatile Path cacheDirectory = configuredCacheDirectory();

    private AvatarCache() {
    }

    public static void init() throws IOException {
        init(configuredCacheDirectory());
    }

    public static synchronized void init(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        if (!Files.isDirectory(normalized)) {
            throw new IOException("Avatar cache path is not a directory: " + normalized);
        }
        cacheDirectory = normalized;
    }

    public static boolean isValidName(String name) {
        return name != null && MINECRAFT_NAME.matcher(name).matches();
    }

    public static boolean has(String name) {
        return cachedPath(name).map(Files::isRegularFile).orElse(false);
    }

    public static boolean isFresh(String name) {
        if (!isValidName(name)) {
            return false;
        }
        return isFreshKey(cacheKey(name));
    }

    public static boolean isFreshKey(String key) {
        Optional<Path> cached = cachedPathForKey(key).filter(Files::isRegularFile);
        if (cached.isEmpty()) {
            return false;
        }

        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(cached.get()).toMillis();
            return age >= 0 && age < cacheTtlMillis();
        } catch (IOException ignored) {
            return false;
        }
    }

    public static Optional<Path> getCachedPath(String name) {
        return cachedPath(name).filter(Files::isRegularFile);
    }

    public static byte[] read(String name) throws IOException {
        return readKey(cacheKey(name));
    }

    public static byte[] readKey(String key) throws IOException {
        Optional<Path> path = cachedPathForKey(key).filter(Files::isRegularFile);
        if (path.isEmpty()) {
            return null;
        }
        long size = Files.size(path.get());
        if (size <= 0 || size > MAX_AVATAR_BYTES) {
            throw new IOException("Cached avatar has an invalid size: " + size);
        }
        return Files.readAllBytes(path.get());
    }

    public static String url(String name) {
        return url(name, null);
    }

    public static String url(String name, String requestedBaseUrl) {
        return urlForKey(cacheKey(name), "name", requestedBaseUrl);
    }

    public static String urlForKey(String key, String requestedBaseUrl) {
        validateCacheKey(key);
        return urlForKey(key, "key", requestedBaseUrl);
    }

    private static String urlForKey(String key, String parameter, String requestedBaseUrl) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String publicBaseUrl = configuredPublicBaseUrl();
        if (publicBaseUrl.isEmpty() && requestedBaseUrl != null && !requestedBaseUrl.isBlank()) {
            publicBaseUrl = requestedBaseUrl.trim().replaceAll("/+$", "");
        }
        String path = "/qo/download/avatar/image?" + parameter + "=" + encodedKey;
        return publicBaseUrl.isEmpty() ? path : publicBaseUrl + path;
    }

    public static CompletableFuture<Path> cacheAsync(String url, String name) {
        if (url == null || url.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Avatar URL is empty."));
        }
        final String key;
        try {
            key = cacheKey(name);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return cacheAsyncForKey(url, key);
    }

    public static CompletableFuture<Path> cacheAsyncForKey(String url, String key) {
        if (url == null || url.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Avatar URL is empty."));
        }
        try {
            validateCacheKey(key);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        CompletableFuture<Path> result = IN_FLIGHT.computeIfAbsent(key, ignored -> {
            CompletableFuture<Path> promise = new CompletableFuture<>();
            startCache(url, key).whenComplete((path, error) -> {
                if (error != null) {
                    promise.completeExceptionally(error);
                } else {
                    promise.complete(path);
                }
            });
            return promise;
        });
        result.whenComplete((ignored, error) -> IN_FLIGHT.remove(key, result));
        return result;
    }

    public static void cache(String url, String name) throws Exception {
        cacheAsync(url, name).get();
    }

    public static String externalKey(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Avatar URL is empty.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(SPECIAL_KEY_PREFIX.length() + digest.length * 2);
            hex.append(SPECIAL_KEY_PREFIX);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CompletableFuture<Path> startCache(String url, String key) {
        final Path target;
        final Path temporary;
        try {
            Path directory = ensureDirectory();
            target = pathForKey(directory, key);
            temporary = Files.createTempFile(directory, "." + key + "-", ".tmp");
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<Path> result = REQUEST.download(url, temporary.toString(), MAX_AVATAR_BYTES)
                .thenApply(ignored -> {
                    try {
                        long size = Files.size(temporary);
                        if (size <= 0 || size > MAX_AVATAR_BYTES) {
                            throw new IOException("Avatar response has an invalid size: " + size);
                        }
                        moveIntoPlace(temporary, target);
                        return target;
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                });

        result.whenComplete((ignored, error) -> {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredCleanup) {
                // A completed atomic move already removed the temporary file.
            }
        });
        return result;
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Optional<Path> cachedPath(String name) {
        if (!isValidName(name)) {
            return Optional.empty();
        }
        return cachedPathForKey(cacheKey(name));
    }

    private static Optional<Path> cachedPathForKey(String key) {
        if (!isValidCacheKey(key)) {
            return Optional.empty();
        }
        return Optional.of(pathForKey(cacheDirectory, key));
    }

    private static Path ensureDirectory() throws IOException {
        Path directory = cacheDirectory;
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory)) {
            throw new IOException("Avatar cache path is not a directory: " + directory);
        }
        return directory;
    }

    private static Path pathForKey(Path directory, String key) {
        return directory.resolve(key + ".png").normalize();
    }

    private static String cacheKey(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid Minecraft username.");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidCacheKey(String key) {
        return key != null && CACHE_KEY.matcher(key).matches();
    }

    private static void validateCacheKey(String key) {
        if (!isValidCacheKey(key)) {
            throw new IllegalArgumentException("Invalid avatar cache key.");
        }
    }

    private static Path configuredCacheDirectory() {
        String configured = System.getProperty("qapi.avatar.cache-dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("QAPI_AVATAR_CACHE_DIR", CachePath);
        }
        return Path.of(configured);
    }

    private static long cacheTtlMillis() {
        String configured = System.getProperty("qapi.avatar.cache-ttl-millis");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault(
                    "QAPI_AVATAR_CACHE_TTL_SECONDS",
                    Long.toString(DEFAULT_CACHE_TTL_MILLIS / 1000L)
            );
            try {
                long seconds = Long.parseLong(configured);
                if (seconds <= 0) {
                    return 0L;
                }
                return seconds > Long.MAX_VALUE / 1000L
                        ? Long.MAX_VALUE
                        : seconds * 1000L;
            } catch (NumberFormatException ignored) {
                return DEFAULT_CACHE_TTL_MILLIS;
            }
        }
        try {
            return Math.max(0L, Long.parseLong(configured));
        } catch (NumberFormatException ignored) {
            return DEFAULT_CACHE_TTL_MILLIS;
        }
    }

    private static String configuredPublicBaseUrl() {
        String configured = System.getProperty("qapi.avatar.public-base-url");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("QAPI_PUBLIC_BASE_URL", "");
        }
        return configured.trim().replaceAll("/+$", "");
    }
}
