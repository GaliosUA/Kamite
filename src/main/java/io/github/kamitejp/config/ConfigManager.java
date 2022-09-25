package io.github.kamitejp.config;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.impl.ConfigImpl;

import io.github.kamitejp.Kamite;
import io.github.kamitejp.util.Result;

public final class ConfigManager {
  private static final Logger LOG = LogManager.getLogger(MethodHandles.lookup().lookupClass());

  private static final String MAIN_CONFIG_FILE_PATH_RELATIVE = "config.hocon";
  private static final String PROFILE_CONFIG_FILE_PATH_RELATIVE_TPL = "config.%s.hocon";
  private static final String DEFAULT_CONFIG_FILE_RESOURCE_PATH = "/config.default.hocon";
  private static final String KNOWN_KEYS_FILE_RESOURCE_PATH = "/known_config_keys.txt";
  private static final String LOOKUP_TARGET_URL_PLACEHOLDER = "{}";

  private final Consumer<Result<Config, String>> configReloadedCb;

  private Config config;
  private List<File> configFiles;
  private ConfigFilesWatcher configFilesWatcher;
  private com.typesafe.config.Config programArgsConfig;

  @SuppressWarnings("WeakerAccess") // Mistaken
  public record ReadSuccess(Config config, List<String> loadedProfileNames) {}

  record ConfigFileEntry(String profileName, File file) {}

  public ConfigManager(Consumer<Result<Config, String>> configReloadedCb) {
    this.configReloadedCb = configReloadedCb;
  }

  public Result<ReadSuccess, String> read(
    Path configDirPath,
    List<String> profileNames,
    Map<String, String> programArgs
  ) {
    var mainConfigPath = configDirPath.resolve(MAIN_CONFIG_FILE_PATH_RELATIVE);

    var ensureRes = ensureMainConfig(configDirPath, mainConfigPath);
    if (ensureRes.isErr()) {
      return Result.Err(ensureRes.err());
    }

    List<ConfigFileEntry> readableProfileConfigFiles = null;
    if (!profileNames.isEmpty()) {
      readableProfileConfigFiles = new ArrayList<>();
      for (var name : profileNames) {
        var relativePath = PROFILE_CONFIG_FILE_PATH_RELATIVE_TPL.formatted(name);
        var path = configDirPath.resolve(relativePath);
        var file = path.toFile();
        if (!file.canRead()) {
          LOG.warn("Config file for the requested profile is not accessible: {}", path);
          continue;
        }
        readableProfileConfigFiles.add(new ConfigFileEntry(name, file));
      }
    }

    try {
      programArgsConfig = configFromProgramArgs(programArgs);
      if (programArgsConfig == null) {
        return Result.Err("Failed to parse program arguments into a Config object");
      }

      configFiles = new ArrayList<>();
      var loadedProfileNames = new ArrayList<String>();
      if (readableProfileConfigFiles != null) {
        for (var configFileEntry : readableProfileConfigFiles) {
          configFiles.add(configFileEntry.file);
          loadedProfileNames.add(configFileEntry.profileName);
        }
      }
      configFiles.add(mainConfigPath.toFile());

      config = read(configFiles, programArgsConfig);
      LOG.debug("Read config: {}", config);

      configFilesWatcher = new ConfigFilesWatcher(configFiles, (Void) -> {
        try {
          var newConfig = reload();
          // PERF: Reject doubled config file change event before parsing the config
          if (!Objects.equals(newConfig, config)) {
            configReloadedCb.accept(Result.Ok(newConfig));
            config = newConfig;
          } else {
            LOG.debug("New config identical to previous, skipping");
          }
        } catch (ConfigException e) {
          configReloadedCb.accept(Result.Err(e.getMessage()));
        }
      });

      return Result.Ok(new ReadSuccess(config, loadedProfileNames));
    } catch (ConfigException e) {
      return Result.Err(e.toString());
    }
  }

  private Config reload() {
    return read(configFiles, programArgsConfig);
  }

  public void destroy() {
    if (configFilesWatcher != null) {
      configFilesWatcher.destroy();
    }
  }

  private Config read(
    List<File> configFiles, com.typesafe.config.Config baseTsConfig
  ) throws ConfigException {
    var tsConfig = baseTsConfig;
    for (var f : configFiles) {
      tsConfig = tsConfig.withFallback(ConfigFactory.parseFile(f));
    }
    tsConfig = tsConfig.resolve();

    var config = new Config(tsConfig);
    validateExtra(config);

    checkForUnknownKeys(tsConfig);

    return config;
  }

  public static boolean isOwnKey(String key) {
    return Character.isLowerCase(key.charAt(0));
  }

  private static Result<Void, String> ensureMainConfig(Path dirPath, Path filePath) {
    if (Files.isReadable(filePath)) {
      return Result.Ok(null);
    }

    LOG.info(
      "Main config file is not accessible: {}. Creating a default config file",
      filePath
    );
    var res = createDefaultConfig(dirPath, filePath);
    if (res.isOk()) {
      LOG.info("Created a default config file at '{}'", filePath);
    }
    return res;
  }

  private static Result<Void, String> createDefaultConfig(Path dirPath, Path filePath) {
    try {
      if (!Files.isDirectory(dirPath)) {
        LOG.info("Creating the config directory: {}", dirPath);
        Files.createDirectory(dirPath);
      }
      Files.copy(
        Objects.requireNonNull(
          ConfigManager.class.getResourceAsStream(DEFAULT_CONFIG_FILE_RESOURCE_PATH)
        ),
        filePath
      );
    } catch (IOException | RuntimeException e) {
      return Result.Err(
        "Failed to create a default config file at '%s': %s".formatted(filePath, e)
      );
    }
    return Result.Ok(null);
  }

  private static com.typesafe.config.Config configFromProgramArgs(Map<String, String> args) {
    try {
      var c = Class.forName("com.typesafe.config.impl.PropertiesParser");
      var fromStringMapMethod = c.getDeclaredMethod("fromStringMap", ConfigOrigin.class, Map.class);
      fromStringMapMethod.setAccessible(true);
      var abstractConfig = fromStringMapMethod.invoke(
        null,
        ConfigImpl.newSimpleOrigin("program arguments"),
        args
      );

      c = Class.forName("com.typesafe.config.impl.AbstractConfigObject");
      var toConfigMethod = c.getDeclaredMethod("toConfig");
      toConfigMethod.setAccessible(true);

      return (com.typesafe.config.Config) toConfigMethod.invoke(abstractConfig);
    } catch (
      ClassNotFoundException
      | NoSuchMethodException
      | InvocationTargetException
      | IllegalAccessException e
    ) {
      LOG.error("Could not read program arguments into a Config object");
    }
    return null;
  }

  private static void checkForUnknownKeys(com.typesafe.config.Config tsConfig) {
    List<String> knownKeys = null;
    try {
      knownKeys = Files.readAllLines(
        Paths.get(ConfigManager.class.getResource(KNOWN_KEYS_FILE_RESOURCE_PATH).toURI()),
        StandardCharsets.UTF_8
      );
    } catch (URISyntaxException e) {
      throw new RuntimeException("Invalid known conifg keys file path");
    } catch (IOException e) {
      throw new RuntimeException("Could not read known config keys file");
    }
    if (knownKeys == null) {
      return;
    }

    List<String> unknownKeys = null;
    for (var entry : tsConfig.entrySet()) {
      var key = entry.getKey();
      if (!isOwnKey(key)) {
        continue;
      }
      if (Kamite.PRECONFIG_ARGS.containsKey(key)) {
        continue;
      }
      if (!knownKeys.contains(key)) {
        if (unknownKeys == null) {
          unknownKeys = new ArrayList<>();
        }
        unknownKeys.add(key);
      }
    }
    if (unknownKeys != null) {
      LOG.warn("Config contains unknown keys: %s".formatted(String.join(", ", unknownKeys)));
    }
  }

  @SuppressWarnings("ThrowsRuntimeException")
  private static void validateExtra(Config config) throws ConfigException {
    if (config.chunk().log() != null) {
      validateStringNonEmptyOrNull(config.chunk().log().dir(), "chunk.log.dir");
    }

    validateExtraList(config.commands().custom(), "commands.custom[%d]", (c, key) -> {
      validateSymbolLength(c.symbol(), key.apply("symbol"));
      validateStringNonEmpty(c.name(), key.apply("name"));
      validateListNonEmpty(c.command(), key.apply("command"));
    });

    validateExtraList(config.lookup().targets(), "lookup.targets[%d]", (t, key) ->{
      validateSymbolLength(t.symbol(), key.apply("symbol"));
      validateStringNonEmpty(t.name(), key.apply("name"));

      var urlKey = key.apply("url");
      validateStringNonEmpty(t.url(), urlKey);
      validateStringContains(t.url(), LOOKUP_TARGET_URL_PLACEHOLDER, urlKey);
    });

    validateStringNonEmptyOrNull(config.ocr().watchDir(), "ocr.watchDir");
    validateStringNonEmptyOrNull(config.ocr().mangaocr().pythonPath(), "ocr.mangaocr.pythonPath");
    validateIntOneOf(config.ocr().ocrspace().engine(), List.of(1, 3), "ocr.ocrspace.engine");

    validateExtraList(config.ocr().regions(), "ocr.regions[%d]", (r, key) -> {
      validateSymbolLength(r.symbol(), key.apply("symbol"));
      validateStringNonEmptyOrNull(r.description(), key.apply("description"));
    });

    validateStringNonEmptyOrNull(config.secrets().ocrspace(), "secrets.ocrspace");
  }

  @SuppressWarnings("ThrowsRuntimeException")
  private static <T> void validateExtraList(
    List<T> list, String keyPrefixTpl, BiConsumer<T, UnaryOperator<String>> validateElementFn
  ) throws ConfigException {
    if (list == null) {
      return;
    }
    for (var i = 0; i < list.size(); i++) {
      var el = list.get(i);
      var keyPrefix = keyPrefixTpl.formatted(i);
      //noinspection ObjectAllocationInLoop
      validateElementFn.accept(el, (key) -> "%s.%s".formatted(keyPrefix, key));
    }
  }

  @SuppressWarnings("ThrowsRuntimeException")
  private static void validateSymbolLength(
    CharSequence symbol, String key
  ) throws ConfigException.BadValue {
    var len = symbol.length();
    if (len < 1 || len > 3) {
      throw new ConfigException.BadValue(key, "should be between 1 and 3 characters");
    }
  }

  @SuppressWarnings("ThrowsRuntimeException")
  private static void validateStringNonEmpty(
    CharSequence s, String key
  ) throws ConfigException.BadValue {
    if (s.isEmpty()) {
      throw new ConfigException.BadValue(key, "should not be empty");
    }
  }

  @SuppressWarnings("ThrowsRuntimeException")
  private static void validateStringNonEmptyOrNull(
    CharSequence s, String key
  ) throws ConfigException.BadValue {
    if (s != null) {
      validateStringNonEmpty(s, key);
    }
  }

  @SuppressWarnings({"SameParameterValue", "ThrowsRuntimeException"})
  private static void validateStringContains(
    String s, String substring, String key
  ) throws ConfigException.BadValue {
    if (!s.contains(substring)) {
      throw new ConfigException.BadValue(key, "should contain '%s'".formatted(substring));
    }
  }

  @SuppressWarnings("ThrowsRuntimeException")
  private static void validateIntOneOf(
    // NOTE: Uses List instead of Set because that way the error message displays the numbers in
    //       order without extra intervention
    int intval, List<Integer> allowed, String key
  ) throws ConfigException.BadValue {
    if (!allowed.contains(intval)) {
      throw new ConfigException.BadValue(key, "should be one of: %s".formatted(allowed.toString()));
    }
  }

  @SuppressWarnings({"rawtypes", "ThrowsRuntimeException"})
  private static void validateListNonEmpty(List list, String key) throws ConfigException.BadValue {
    if (list.isEmpty()) {
      throw new ConfigException.BadValue(key, "should not be empty");
    }
  }
}
