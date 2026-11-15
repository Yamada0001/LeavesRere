package org.leavesmc.leaves.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.LeavesLogger;
import org.leavesmc.leaves.config.annotations.GlobalConfig;
import org.leavesmc.leaves.config.annotations.GlobalConfigCategory;
import org.leavesmc.leaves.config.annotations.TransferConfig;
import org.leavesmc.leaves.config.api.ConfigValidator;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GlobalConfigManager {

    public final static String CONFIG_START = "settings.";

    private static final Map<String, VerifiedConfig> verifiedConfigs = new HashMap<>();
    private static final Map<String, String[]> comments = new HashMap<>();
    private static final ConfigNode rootNode = new ConfigNode("");

    private static boolean loaded = false;

    public static void init() {
        if (loaded) {
            return;
        }

        for (Field field : LeavesConfig.class.getDeclaredFields()) {
            initField(field, null, CONFIG_START);
        }
        verifiedConfigs.values().forEach(config -> config.validator().runAfterLoader(config.get(), false));
        clearRemovedConfig();
        LeavesConfig.save();

        loaded = true;
    }

    public static void reload() {
        if (!loaded) {
            return;
        }

        for (Field field : LeavesConfig.class.getDeclaredFields()) {
            initField(field, null, CONFIG_START);
        }
        verifiedConfigs.values().stream().filter(config -> !config.lock()).forEach(config -> config.validator().runAfterLoader(config.get(), true));
        clearRemovedConfig();
        LeavesConfig.save();
    }

    private static void initField(@NotNull Field field, @Nullable Object upstreamField, @NotNull String upstreamPath) {
        if (upstreamField != null || Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true);

            for (TransferConfig config : field.getAnnotationsByType(TransferConfig.class)) {
                VerifiedTransferConfig.build(config, field, upstreamField).run();
            }

            GlobalConfig globalConfig = field.getAnnotation(GlobalConfig.class);
            if (globalConfig != null) {
                initConfig(field, globalConfig, upstreamField, upstreamPath);
                return;
            }

            GlobalConfigCategory globalCategory = field.getType().getAnnotation(GlobalConfigCategory.class);
            if (globalCategory != null) {
                initCategory(field, globalCategory, upstreamField, upstreamPath);
            }
        }
    }

    private static void initCategory(@NotNull Field categoryField, @NotNull GlobalConfigCategory globalCategory, @Nullable Object upstreamField, @NotNull String upstreamPath) {
        try {
            Object category = categoryField.get(upstreamField);
            String categoryPath = upstreamPath + globalCategory.value() + ".";
            for (Field field : categoryField.getType().getDeclaredFields()) {
                initField(field, category, categoryPath);
            }
            traverseToNodeOrCreate(categoryPath.substring(CONFIG_START.length()));
        } catch (Exception e) {
            LeavesLogger.LOGGER.error("Failure to load leaves config {}", upstreamPath, e);
        }
    }

    private static void initConfig(@NotNull Field field, GlobalConfig globalConfig, @Nullable Object upstreamField, @NotNull String upstreamPath) {
        try {
            if (loaded && globalConfig.lock()) {
                return;
            }

            VerifiedConfig verifiedConfig = VerifiedConfig.build(globalConfig, field, upstreamField, upstreamPath);

            ConfigValidator<? super Object> validator = verifiedConfig.validator();
            String path = verifiedConfig.path();

            Object defValue = validator.saveConvert(field.get(upstreamField));
            LeavesConfig.config.addDefault(path, defValue);

            String[] comment = globalConfig.comment();
            if (comment.length > 0) {
                // addDefault 登记的键在 section 里并不是真实存在的，Bukkit 保存时不会输出其注释，
                // 这里用当前值把键物化一次，注释才能写进 leaves.yml。
                LeavesConfig.config.set(path, LeavesConfig.config.get(path, defValue));
                LeavesConfig.config.setComments(path, List.of(comment));
            }

            try {
                Object savedValue = LeavesConfig.config.get(path);
                if (savedValue == null) {
                    throw new IllegalArgumentException("?");
                }

                savedValue = validator.loadConvert(savedValue);
                validator.verify(null, savedValue);
                field.set(upstreamField, savedValue);
            } catch (IllegalArgumentException | ClassCastException e) {
                LeavesConfig.config.set(path, defValue);
                LeavesLogger.LOGGER.warn("{}, reset to {}", e.getMessage(), defValue);
            }

            verifiedConfigs.put(path.substring(CONFIG_START.length()), verifiedConfig);
            if (comment.length > 0) {
                comments.put(path.substring(CONFIG_START.length()), comment);
            }
            traverseToNodeOrCreate(path.substring(CONFIG_START.length()));
        } catch (Exception e) {
            LeavesLogger.LOGGER.error("Failure to load leaves config", e);
            throw new RuntimeException();
        }
    }

    private static void clearRemovedConfig() {
        for (String key : LeavesConfig.config.getKeys(true)) {
            if (!key.startsWith(CONFIG_START) || key.equals(CONFIG_START)) {
                continue;
            }

            String keyWithoutPrefix = key.substring(CONFIG_START.length());
            if (!verifiedConfigs.containsKey(keyWithoutPrefix) && getVerifiedConfigSubPaths(keyWithoutPrefix + ".").isEmpty()) {
                LeavesConfig.config.set(key, null);
            }
        }
    }

    public static VerifiedConfig getVerifiedConfig(String path) {
        return verifiedConfigs.get(path);
    }

    @Nullable
    public static String[] getComment(@NotNull String path) {
        return comments.get(path);
    }

    @NotNull
    public static Map<String, VerifiedConfig> getAllConfigs() {
        return Collections.unmodifiableMap(verifiedConfigs);
    }

    private static class ConfigNode {
        String name;
        Map<String, ConfigNode> children;

        public ConfigNode(String name) {
            this.name = name;
            this.children = new HashMap<>();
        }
    }

    private static void traverseToNodeOrCreate(@NotNull String path) {
        String[] parts = path.split("\\.");
        ConfigNode current = rootNode;

        for (String part : parts) {
            if (!part.isEmpty()) {
                current = current.children.computeIfAbsent(part, ConfigNode::new);
            }
        }
    }

    @Nullable
    private static ConfigNode traverseToNode(@NotNull String path) {
        int index = path.lastIndexOf('.');
        String[] parts = index == -1 ? new String[0] : path.substring(0, index).split("\\.");

        ConfigNode current = rootNode;
        for (String part : parts) {
            current = current.children.get(part);
            if (current == null) {
                return null;
            }
        }

        return current;
    }

    @NotNull
    public static Set<String> getVerifiedConfigSubPaths(@NotNull String prefix) {
        ConfigNode current = traverseToNode(prefix);
        if (current == null) {
            return Collections.emptySet();
        }
        return current.children.keySet();
    }
}
