package dev.codex.pluginsreload;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.UnknownDependencyException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class PluginsReloadPlugin extends JavaPlugin implements TabExecutor {
    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "PluginsReload" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "unload", "help", "version", "update");
    private static final Pattern JSON_FIELD_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final String UPDATE_URL = "https://api.github.com/repos/PueSeul/pluginreload/releases/latest";
    private static final String PROJECT_URL = "https://github.com/PueSeul/pluginreload/releases";
    private static final long MAX_UPDATE_SIZE_BYTES = 50L * 1024L * 1024L;
    private final Map<String, String> pendingUnloadConfirmations = new LinkedHashMap<>();
    private final Map<String, String> pendingUpdateConfirmations = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("pl") != null) {
            getCommand("pl").setExecutor(this);
            getCommand("pl").setTabCompleter(this);
        }
        checkUpdateOnStartup();
    }

    private void checkUpdateOnStartup() {
        CompletableFuture.supplyAsync(this::checkUpdate)
                .thenAccept(result -> Bukkit.getScheduler().runTask(this, () -> logStartupUpdateResult(result)))
                .exceptionally(throwable -> {
                    Bukkit.getScheduler().runTask(this, () ->
                            getLogger().warning("Startup update check failed: " + throwable.getMessage()));
                    return null;
                });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                clearPendingUnload(sender);
                clearPendingUpdate(sender);
                handleReload(sender);
                break;
            case "unload":
                clearPendingUpdate(sender);
                handleUnload(sender, args);
                break;
            case "version":
                clearPendingUnload(sender);
                clearPendingUpdate(sender);
                sendVersion(sender);
                break;
            case "update":
                clearPendingUnload(sender);
                handleUpdate(sender);
                break;
            default:
                clearPendingUnload(sender);
                clearPendingUpdate(sender);
                sender.sendMessage(PREFIX + ChatColor.RED + "알 수 없는 명령어입니다. " + ChatColor.YELLOW + "/pl help" + ChatColor.RED + "를 입력해 주세요.");
                break;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(subcommand -> subcommand.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("unload")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .map(Plugin::getName)
                    .filter(name -> !name.equalsIgnoreCase(getName()))
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "PluginsReload 명령어");
        sender.sendMessage(ChatColor.YELLOW + "/pl reload" + ChatColor.GRAY + " - plugins 폴더의 새 플러그인 jar를 인식하고 활성화합니다.");
        sender.sendMessage(ChatColor.YELLOW + "/pl unload <플러그인>" + ChatColor.GRAY + " - 로드된 플러그인을 비활성화하고 목록에서 제거합니다.");
        sender.sendMessage(ChatColor.YELLOW + "/pl help" + ChatColor.GRAY + " - 명령어 목록과 간단한 설명을 봅니다.");
        sender.sendMessage(ChatColor.YELLOW + "/pl version" + ChatColor.GRAY + " - 현재 PluginsReload 버전을 봅니다.");
        sender.sendMessage(ChatColor.YELLOW + "/pl update" + ChatColor.GRAY + " - 새 버전을 확인하고 업데이트를 준비합니다.");
    }
    private void sendVersion(CommandSender sender) {
        sender.sendMessage(PREFIX + ChatColor.GREEN + "현재 버전: " + ChatColor.WHITE + getDescription().getVersion());
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("pluginsreload.reload")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "권한이 없습니다: pluginsreload.reload");
            return;
        }

        File pluginsDirectory = getDataFolder().getParentFile();
        LoadReport report = loadNewPlugins(pluginsDirectory);

        if (report.loaded().isEmpty() && report.failures().isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "새로 인식할 플러그인이 없습니다.");
            return;
        }

        if (!report.loaded().isEmpty()) {
            sender.sendMessage(PREFIX + ChatColor.GREEN + "로드 완료: " + ChatColor.WHITE + String.join(", ", report.loaded()));
        }

        report.failures().forEach((fileName, reason) ->
                sender.sendMessage(PREFIX + ChatColor.RED + fileName + " 로드 실패: " + ChatColor.GRAY + reason));
    }

    private void handleUnload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pluginsreload.unload")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "권한이 없습니다: pluginsreload.unload");
            return;
        }

        if (args.length != 2) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "사용법: /pl unload <플러그인>");
            return;
        }

        Plugin plugin = Bukkit.getPluginManager().getPlugin(args[1]);
        if (plugin == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "플러그인을 찾을 수 없습니다: " + ChatColor.WHITE + args[1]);
            return;
        }

        if (plugin == this || plugin.getName().equalsIgnoreCase(getName())) {
            sender.sendMessage(PREFIX + ChatColor.RED + "PluginsReload 자신은 unload 할 수 없습니다.");
            return;
        }

        String senderKey = unloadConfirmationKey(sender);
        String pluginKey = plugin.getName().toLowerCase(Locale.ROOT);
        String pendingPlugin = pendingUnloadConfirmations.get(senderKey);
        if (!pluginKey.equals(pendingPlugin)) {
            pendingUnloadConfirmations.put(senderKey, pluginKey);
            sender.sendMessage(PREFIX + ChatColor.RED + "경고: " + ChatColor.WHITE + plugin.getName() + ChatColor.RED + " 플러그인을 언로드하면 해당 플러그인의 기능과 명령어가 즉시 비활성화됩니다.");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "정말 언로드하려면 같은 명령어를 한 번 더 입력하세요: "
                    + ChatColor.WHITE + "/pl unload " + plugin.getName());
            return;
        }

        pendingUnloadConfirmations.remove(senderKey);
        try {
            unloadPlugin(plugin);
            sender.sendMessage(PREFIX + ChatColor.GREEN + "언로드 완료: " + ChatColor.WHITE + plugin.getName());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            clearPendingUnload(sender);
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to unload plugin " + plugin.getName(), exception);
            sender.sendMessage(PREFIX + ChatColor.RED + "언로드 실패: " + ChatColor.GRAY + exception.getMessage());
        }
    }

    private void clearPendingUnload(CommandSender sender) {
        pendingUnloadConfirmations.remove(unloadConfirmationKey(sender));
    }

    private String unloadConfirmationKey(CommandSender sender) {
        return sender.getClass().getName() + ":" + sender.getName().toLowerCase(Locale.ROOT);
    }

    private void unloadPlugin(Plugin plugin) throws ReflectiveOperationException {
        Bukkit.getPluginManager().disablePlugin(plugin);
        removePluginCommands(plugin);
        removePluginFromManager(plugin);
    }

    private void removePluginCommands(Plugin plugin) throws ReflectiveOperationException {
        Object commandMap = getField(Bukkit.getServer(), "commandMap");
        Object knownCommandsObject = getField(commandMap, "knownCommands");
        if (!(knownCommandsObject instanceof Map)) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsObject;
        List<Command> removedCommands = new ArrayList<>();
        List<String> keysToRemove = new ArrayList<>();

        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            Command command = entry.getValue();
            if (command instanceof PluginIdentifiableCommand
                    && ((PluginIdentifiableCommand) command).getPlugin() == plugin) {
                removedCommands.add(command);
                keysToRemove.add(entry.getKey());
            }
        }

        for (Command command : removedCommands) {
            command.unregister((org.bukkit.command.CommandMap) commandMap);
        }
        for (String key : keysToRemove) {
            knownCommands.remove(key);
        }
    }

    private void removePluginFromManager(Plugin plugin) throws ReflectiveOperationException {
        Object pluginManager = Bukkit.getPluginManager();
        removePluginFromStorage(pluginManager, plugin);

        Object paperPluginManager = getOptionalField(pluginManager, "paperPluginManager");
        if (paperPluginManager != null && paperPluginManager != pluginManager) {
            removePluginFromStorage(paperPluginManager, plugin);
        }

        Object instanceManager = getOptionalField(pluginManager, "instanceManager");
        if (instanceManager == null && paperPluginManager != null) {
            instanceManager = getOptionalField(paperPluginManager, "instanceManager");
        }
        if (instanceManager != null && instanceManager != pluginManager && instanceManager != paperPluginManager) {
            removePluginFromStorage(instanceManager, plugin);
        }
    }

    private void removePluginFromStorage(Object storage, Plugin plugin) throws ReflectiveOperationException {
        Object pluginsObject = getOptionalField(storage, "plugins");
        if (pluginsObject instanceof List) {
            ((List<?>) pluginsObject).remove(plugin);
        }

        Object lookupNamesObject = getOptionalField(storage, "lookupNames");
        if (lookupNamesObject instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Plugin> lookupNames = (Map<String, Plugin>) lookupNamesObject;
            List<String> keysToRemove = new ArrayList<>();
            for (Map.Entry<String, Plugin> entry : lookupNames.entrySet()) {
                if (entry.getValue() == plugin) {
                    keysToRemove.add(entry.getKey());
                }
            }
            for (String key : keysToRemove) {
                lookupNames.remove(key);
            }
        }
    }

    private Object getOptionalField(Object target, String name) throws ReflectiveOperationException {
        try {
            return getField(target, name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private Object getField(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private LoadReport loadNewPlugins(File pluginsDirectory) {
        File[] jars = pluginsDirectory.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return new LoadReport(Collections.emptyList(), Collections.emptyMap());
        }

        List<File> candidates = new ArrayList<>(Arrays.asList(jars));
        candidates.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        Set<String> loadedPluginNames = new HashSet<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            loadedPluginNames.add(plugin.getName().toLowerCase(Locale.ROOT));
        }

        candidates.removeIf(file -> {
            String pluginName = readPluginName(file);
            return pluginName == null || loadedPluginNames.contains(pluginName.toLowerCase(Locale.ROOT));
        });

        List<String> loaded = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        boolean progressed;

        do {
            progressed = false;
            List<File> remaining = new ArrayList<>();
            failures.clear();

            for (File file : candidates) {
                try {
                    Plugin plugin = Bukkit.getPluginManager().loadPlugin(file);
                    if (plugin == null) {
                        failures.put(file.getName(), "플러그인 매니저가 null을 반환했습니다.");
                        remaining.add(file);
                        continue;
                    }

                    Bukkit.getPluginManager().enablePlugin(plugin);
                    loaded.add(plugin.getName());
                    progressed = true;
                } catch (UnknownDependencyException exception) {
                    failures.put(file.getName(), "필수 의존성이 아직 로드되지 않았습니다: " + exception.getMessage());
                    remaining.add(file);
                } catch (InvalidPluginException | InvalidDescriptionException exception) {
                    failures.put(file.getName(), exception.getMessage());
                } catch (RuntimeException exception) {
                    failures.put(file.getName(), exception.getClass().getSimpleName() + ": " + exception.getMessage());
                }
            }

            candidates = remaining;
        } while (progressed && !candidates.isEmpty());

        return new LoadReport(loaded, failures);
    }

    private String readPluginName(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                return null;
            }

            try (InputStream inputStream = jar.getInputStream(entry)) {
                return new PluginDescriptionFile(inputStream).getName();
            }
        } catch (IOException | InvalidDescriptionException exception) {
            return null;
        }
    }

    private void handleUpdate(CommandSender sender) {
        if (!sender.hasPermission("pluginsreload.update")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "권한이 없습니다: pluginsreload.update");
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.GRAY + "업데이트를 확인하는 중입니다...");

        CompletableFuture.supplyAsync(this::checkUpdate)
                .thenAccept(result -> Bukkit.getScheduler().runTask(this, () -> handleUpdateResult(sender, result)))
                .exceptionally(throwable -> {
                    Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage(PREFIX + ChatColor.RED + "업데이트 확인 실패: " + ChatColor.GRAY + throwable.getMessage()));
                    return null;
                });
    }

    private UpdateResult checkUpdate() {
        int timeoutMillis = Math.max(1, getConfig().getInt("update-timeout-seconds", 8)) * 1000;

        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(UPDATE_URL).toURL().openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "PluginsReload/" + getDescription().getVersion());

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return UpdateResult.failure("HTTP " + responseCode);
            }

            String response = readAll(connection);
            UpdateInfo latest = parseUpdateInfo(response);
            if (isBlank(latest.version())) {
                return UpdateResult.failure("응답에서 버전 정보를 찾지 못했습니다.");
            }

            return UpdateResult.success(latest);
        } catch (IllegalArgumentException | IOException exception) {
            return UpdateResult.failure(exception.getMessage());
        }
    }

    private String readAll(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString().trim();
        }
    }

    private UpdateInfo parseUpdateInfo(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("{")) {
            String version = jsonField(trimmed, "tag_name");
            if (isBlank(version)) {
                version = jsonField(trimmed, "version");
            }
            String pageUrl = jsonField(trimmed, "html_url");
            String assetName = "";
            String downloadUrl = "";
            Matcher assetMatcher = Pattern.compile(
                    "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]*PluginsReload[^\\\"]*\\.jar)\\\"[\\s\\S]*?"
                            + "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                    Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (assetMatcher.find()) {
                assetName = unescapeJson(assetMatcher.group(1));
                downloadUrl = unescapeJson(assetMatcher.group(2));
            }
            return new UpdateInfo(version, pageUrl, assetName, downloadUrl);
        }

        if (trimmed.startsWith("[")) {
            String version = jsonField(trimmed, "version_number");
            String url = jsonField(trimmed, "url");
            return new UpdateInfo(version, url, "", "");
        }

        String[] lines = trimmed.split("\\R");
        return new UpdateInfo(lines.length == 0 ? "" : lines[0].trim(), "", "", "");
    }

    private String jsonField(String json, String fieldName) {
        Matcher matcher = Pattern.compile(String.format(JSON_FIELD_PATTERN.pattern(), Pattern.quote(fieldName))).matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return unescapeJson(matcher.group(1));
    }

    private String unescapeJson(String value) {
        return value.replace("\\/", "/").replace("\\\"", "\"");
    }

    private void handleUpdateResult(CommandSender sender, UpdateResult result) {
        if (!result.success()) {
            clearPendingUpdate(sender);
            sender.sendMessage(PREFIX + ChatColor.RED + "업데이트 확인 실패: " + ChatColor.GRAY + result.error());
            return;
        }

        String currentVersion = getDescription().getVersion();
        UpdateInfo latest = result.info();
        if (compareVersions(latest.version(), currentVersion) <= 0) {
            clearPendingUpdate(sender);
            sender.sendMessage(PREFIX + ChatColor.GREEN + "현재 버전이 최신버전입니다. "
                    + ChatColor.GRAY + "(v" + normalizeVersion(currentVersion) + ")");
            return;
        }

        if (isBlank(latest.downloadUrl()) || isBlank(latest.assetName())) {
            clearPendingUpdate(sender);
            sender.sendMessage(PREFIX + ChatColor.RED + "최신 릴리스에서 PluginsReload JAR 파일을 찾지 못했습니다.");
            sender.sendMessage(PREFIX + ChatColor.AQUA + "릴리스 링크: " + ChatColor.WHITE + updatePageUrl(latest));
            return;
        }

        String confirmationKey = updateConfirmationKey(sender);
        String pendingVersion = pendingUpdateConfirmations.get(confirmationKey);
        if (!latest.version().equalsIgnoreCase(pendingVersion)) {
            pendingUpdateConfirmations.put(confirmationKey, latest.version());
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "새 버전이 있습니다: " + ChatColor.WHITE + latest.version()
                    + ChatColor.GRAY + " (현재 " + currentVersion + ")");
            sender.sendMessage(PREFIX + ChatColor.RED + "업데이트 파일을 내려받으려면 같은 명령어를 한 번 더 입력하세요: "
                    + ChatColor.WHITE + "/pl update");
            return;
        }

        clearPendingUpdate(sender);
        sender.sendMessage(PREFIX + ChatColor.GRAY + latest.version() + " 업데이트를 다운로드하는 중입니다...");
        CompletableFuture.supplyAsync(() -> downloadUpdate(latest))
                .thenAccept(downloadResult -> Bukkit.getScheduler().runTask(this,
                        () -> sendDownloadResult(sender, latest, downloadResult)))
                .exceptionally(throwable -> {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(
                            PREFIX + ChatColor.RED + "업데이트 다운로드 실패: " + ChatColor.GRAY + throwable.getMessage()));
                    return null;
                });
    }

    private DownloadResult downloadUpdate(UpdateInfo latest) {
        int timeoutMillis = Math.max(1, getConfig().getInt("update-timeout-seconds", 8)) * 1000;
        File pluginsDirectory = getDataFolder().getParentFile();
        String updateFolderName = Bukkit.getUpdateFolder();
        if (isBlank(updateFolderName)) {
            return DownloadResult.failure("bukkit.yml의 update-folder가 비활성화되어 있습니다.");
        }

        File updateDirectory = new File(pluginsDirectory, updateFolderName);
        File targetFile = new File(updateDirectory, getFile().getName());
        File temporaryFile = new File(updateDirectory, targetFile.getName() + ".download");

        try {
            if (targetFile.getCanonicalFile().equals(getFile().getCanonicalFile())) {
                return DownloadResult.failure("업데이트 폴더가 현재 plugins 폴더와 같아 안전하게 저장할 수 없습니다.");
            }
            Files.createDirectories(updateDirectory.toPath());
            HttpURLConnection connection = (HttpURLConnection) URI.create(latest.downloadUrl()).toURL().openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "PluginsReload/" + getDescription().getVersion());
            connection.setRequestProperty("Accept", "application/octet-stream");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return DownloadResult.failure("HTTP " + responseCode);
            }

            long declaredSize = connection.getContentLengthLong();
            if (declaredSize > MAX_UPDATE_SIZE_BYTES) {
                return DownloadResult.failure("업데이트 파일이 허용 크기(50MB)를 초과합니다.");
            }

            long downloaded = 0L;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporaryFile))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    downloaded += read;
                    if (downloaded > MAX_UPDATE_SIZE_BYTES) {
                        throw new IOException("업데이트 파일이 허용 크기(50MB)를 초과합니다.");
                    }
                    output.write(buffer, 0, read);
                }
            }

            validateDownloadedPlugin(temporaryFile, latest.version());
            moveUpdateIntoPlace(temporaryFile, targetFile);
            return DownloadResult.success(targetFile);
        } catch (IllegalArgumentException | IOException exception) {
            try {
                Files.deleteIfExists(temporaryFile.toPath());
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            return DownloadResult.failure(exception.getMessage());
        }
    }

    private void validateDownloadedPlugin(File jarFile, String expectedVersion) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry pluginEntry = jar.getJarEntry("plugin.yml");
            if (pluginEntry == null) {
                throw new IOException("다운로드한 파일에 plugin.yml이 없습니다.");
            }

            PluginDescriptionFile description;
            try (InputStream input = jar.getInputStream(pluginEntry)) {
                description = new PluginDescriptionFile(input);
            } catch (InvalidDescriptionException exception) {
                throw new IOException("다운로드한 plugin.yml을 읽을 수 없습니다.", exception);
            }

            if (!getName().equalsIgnoreCase(description.getName())) {
                throw new IOException("다운로드한 JAR의 플러그인 이름이 일치하지 않습니다: " + description.getName());
            }
            if (compareVersions(description.getVersion(), expectedVersion) != 0) {
                throw new IOException("다운로드한 JAR의 버전이 릴리스 버전과 일치하지 않습니다: "
                        + description.getVersion() + " / " + expectedVersion);
            }
        }
    }

    private void moveUpdateIntoPlace(File temporaryFile, File targetFile) throws IOException {
        try {
            Files.move(temporaryFile.toPath(), targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void sendDownloadResult(CommandSender sender, UpdateInfo latest, DownloadResult result) {
        if (!result.success()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "업데이트 다운로드 실패: " + ChatColor.GRAY + result.error());
            sender.sendMessage(PREFIX + ChatColor.AQUA + "릴리스 링크: " + ChatColor.WHITE + updatePageUrl(latest));
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.GREEN + "업데이트 준비 완료: " + ChatColor.WHITE + latest.version());
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "서버를 완전히 재시작하면 새 버전이 적용됩니다.");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "저장 위치: " + result.file().getPath());
    }

    private String updatePageUrl(UpdateInfo latest) {
        return isBlank(latest.pageUrl()) ? PROJECT_URL : latest.pageUrl();
    }

    private String updateConfirmationKey(CommandSender sender) {
        return sender.getClass().getName() + ":" + sender.getName().toLowerCase(Locale.ROOT);
    }

    private void clearPendingUpdate(CommandSender sender) {
        pendingUpdateConfirmations.remove(updateConfirmationKey(sender));
    }

    private void logStartupUpdateResult(UpdateResult result) {
        if (!result.success()) {
            getLogger().warning("Startup update check failed: " + result.error());
            return;
        }

        String currentVersion = getDescription().getVersion();
        UpdateInfo latest = result.info();
        if (compareVersions(latest.version(), currentVersion) <= 0) {
            getLogger().info("PluginsReload is up to date. Current version: " + currentVersion);
            return;
        }

        String downloadUrl = updatePageUrl(latest);
        getLogger().warning("A new PluginsReload version is available: " + latest.version() + " (current " + currentVersion + ")");
        if (!isBlank(downloadUrl)) {
            getLogger().warning("Update link: " + downloadUrl);
        }
    }

    private int compareVersions(String left, String right) {
        String normalizedLeft = normalizeVersion(left);
        String normalizedRight = normalizeVersion(right);
        List<Integer> leftParts = versionParts(normalizedLeft);
        List<Integer> rightParts = versionParts(normalizedRight);
        int max = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < max; index++) {
            int leftPart = index < leftParts.size() ? leftParts.get(index) : 0;
            int rightPart = index < rightParts.size() ? rightParts.get(index) : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return normalizedLeft.compareToIgnoreCase(normalizedRight);
    }

    private String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.length() > 1
                && (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V')
                && Character.isDigit(normalized.charAt(1))) {
            return normalized.substring(1);
        }
        return normalized;
    }

    private List<Integer> versionParts(String version) {
        String[] rawParts = version.split("[^0-9]+");
        List<Integer> parts = new ArrayList<>();
        for (String rawPart : rawParts) {
            if (isBlank(rawPart)) {
                continue;
            }
            try {
                parts.add(Integer.parseInt(rawPart));
            } catch (NumberFormatException ignored) {
                parts.add(0);
            }
        }
        return parts;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class LoadReport {
        private final List<String> loaded;
        private final Map<String, String> failures;

        private LoadReport(List<String> loaded, Map<String, String> failures) {
            this.loaded = loaded;
            this.failures = failures;
        }

        private List<String> loaded() {
            return loaded;
        }

        private Map<String, String> failures() {
            return failures;
        }
    }

    private static final class UpdateInfo {
        private final String version;
        private final String pageUrl;
        private final String assetName;
        private final String downloadUrl;

        private UpdateInfo(String version, String pageUrl, String assetName, String downloadUrl) {
            this.version = version == null ? "" : version;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.assetName = assetName == null ? "" : assetName;
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
        }

        private String version() {
            return version;
        }

        private String pageUrl() {
            return pageUrl;
        }

        private String assetName() {
            return assetName;
        }

        private String downloadUrl() {
            return downloadUrl;
        }
    }

    private static final class UpdateResult {
        private final boolean success;
        private final UpdateInfo info;
        private final String error;

        private UpdateResult(boolean success, UpdateInfo info, String error) {
            this.success = success;
            this.info = info;
            this.error = error;
        }

        private boolean success() {
            return success;
        }

        private UpdateInfo info() {
            return info;
        }

        private String error() {
            return error;
        }

        static UpdateResult success(UpdateInfo info) {
            return new UpdateResult(true, info, "");
        }

        static UpdateResult failure(String error) {
            return new UpdateResult(false, new UpdateInfo("", "", "", ""),
                    error == null ? "알 수 없는 오류" : error);
        }
    }

    private static final class DownloadResult {
        private final boolean success;
        private final File file;
        private final String error;

        private DownloadResult(boolean success, File file, String error) {
            this.success = success;
            this.file = file;
            this.error = error;
        }

        private boolean success() {
            return success;
        }

        private File file() {
            return file;
        }

        private String error() {
            return error;
        }

        static DownloadResult success(File file) {
            return new DownloadResult(true, file, "");
        }

        static DownloadResult failure(String error) {
            return new DownloadResult(false, null, error == null ? "알 수 없는 오류" : error);
        }
    }
}
