/*
 * This file is part of PlaceholderAPI
 *
 * PlaceholderAPI
 * Copyright (c) 2015 - 2020 PlaceholderAPI Team
 *
 * PlaceholderAPI free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PlaceholderAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package me.clip.placeholderapi.expansion.manager;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

public final class CloudExpansionManager {

  @NotNull
  private static final String API_URL = "http://api.extendedclip.com/v2/";

  @NotNull
  private static final Gson GSON = new Gson();
  @NotNull
  private static final Type TYPE = new TypeToken<Map<String, CloudExpansion>>() {}.getType();

  @NotNull
  private final Collector<CloudExpansion, ?, Map<String, CloudExpansion>> INDEXED_NAME_COLLECTOR = Collectors
      .toMap(CloudExpansionManager::toIndexName, Function.identity());


  @NotNull
  private final PlaceholderAPIPlugin plugin;

  @NotNull
  private final Map<String, CloudExpansion> cache = new HashMap<>();
  @NotNull
  private final Map<String, CompletableFuture<File>> await = new ConcurrentHashMap<>();


  public CloudExpansionManager(@NotNull final PlaceholderAPIPlugin plugin) {
    this.plugin = plugin;
  }

  @NotNull
  private static String toIndexName(@NotNull final String name) {
    return name.toLowerCase().replace(' ', '_');
  }

  @NotNull
  private static String toIndexName(@NotNull final CloudExpansion expansion) {
    return toIndexName(expansion.getName());
  }

  public void load() {
    clean();
    fetch(plugin.getPlaceholderAPIConfig().cloudAllowUnverifiedExpansions());
  }

  public void kill() {
    clean();
  }

  @NotNull
  @Unmodifiable
  public Map<String, CloudExpansion> getCloudExpansions() {
    return ImmutableMap.copyOf(cache);
  }

  @NotNull
  @Unmodifiable
  public Map<String, CloudExpansion> getCloudExpansionsInstalled() {
    if (cache.isEmpty()) {
      return Collections.emptyMap();
    }

    return cache.values()
        .stream()
        .filter(CloudExpansion::hasExpansion)
        .collect(INDEXED_NAME_COLLECTOR);
  }

  @NotNull
  @Unmodifiable
  public Map<String, CloudExpansion> getCloudExpansionsByAuthor(@NotNull final String author) {
    if (cache.isEmpty()) {
      return Collections.emptyMap();
    }

    return cache.values()
        .stream()
        .filter(expansion -> author.equalsIgnoreCase(expansion.getAuthor()))
        .collect(INDEXED_NAME_COLLECTOR);
  }

  @NotNull
  @Unmodifiable
  public Set<String> getCloudExpansionAuthors() {
    return cache.values().stream().map(CloudExpansion::getAuthor).collect(Collectors.toSet());
  }

  public int getCloudExpansionAuthorCount() {
    return getCloudExpansionAuthors().size();
  }

  public int getCloudUpdateCount() {
    return ((int) plugin.getLocalExpansionManager()
        .getExpansions()
        .stream()
        .filter(expansion -> findCloudExpansionByName(expansion.getName())
            .map(CloudExpansion::shouldUpdate).orElse(false))
        .count());
  }

  @NotNull
  public Optional<CloudExpansion> findCloudExpansionByName(@NotNull final String name) {
    return Optional.ofNullable(cache.get(toIndexName(name)));
  }

  public void clean() {
    cache.clear();

    await.values().forEach(future -> future.cancel(true));
    await.clear();
  }

  public void fetch(final boolean allowUnverified) {
    plugin.getLogger().info("Fetching available expansion information...");

    CompletableFuture<Map<String, CloudExpansion>> future = CompletableFuture.supplyAsync(() -> {
      final Map<String, CloudExpansion> values = new HashMap<>();

      try {
        //noinspection UnstableApiUsage
        final String json = Resources.toString(new URL(API_URL), StandardCharsets.UTF_8);
        values.putAll(GSON.fromJson(json, TYPE));
      } catch (final IOException ex) {
        throw new CompletionException(ex);
      }

      values.values().removeIf(value -> value.getLatestVersion() == null
          || value.getVersion(value.getLatestVersion()) == null);

      return values;
    });

    if (!allowUnverified) {
      future = future.thenApplyAsync((values) -> {
        values.values().removeIf(expansion -> !expansion.isVerified());
        return values;
      });
    }

    future = future.thenApplyAsync((values) -> {

      values.forEach((name, expansion) -> {
        expansion.setName(name);

        final Optional<PlaceholderExpansion> local = plugin.getLocalExpansionManager()
            .findExpansionByName(name);
        if (local.isPresent() && local.get().isRegistered()) {
          expansion.setHasExpansion(true);
          expansion.setShouldUpdate(!local.get().getVersion().equals(expansion.getLatestVersion()));
        }
      });

      return values;
    });

    future.whenComplete((expansions, exception) -> {

      if (exception != null) {
        plugin.getLogger()
            .log(Level.WARNING, "failed to download expansion information", exception);
        return;
      }

      for (final CloudExpansion expansion : expansions.values()) {
        this.cache.put(toIndexName(expansion), expansion);
      }
    });
  }

  public boolean isDownloading(@NotNull final CloudExpansion expansion) {
    return await.containsKey(toIndexName(expansion));
  }

  @NotNull
  public CompletableFuture<File> downloadExpansion(@NotNull final CloudExpansion expansion,
      @NotNull final CloudExpansion.Version version) {
    if (PlaceholderAPI.isBlocked(expansion.getName())) {
      throw new UnsupportedOperationException("This expansion isn't supported on Minehut.");
    }
    final CompletableFuture<File> previous = await.get(toIndexName(expansion));
    if (previous != null) {
      return previous;
    }

    final File file = new File(plugin.getLocalExpansionManager().getExpansionsFolder(),
        "Expansion-" + toIndexName(expansion) + ".jar");

    final CompletableFuture<File> download = CompletableFuture.supplyAsync(() -> {
      try (final ReadableByteChannel source = Channels.newChannel(new URL(version.getUrl())
          .openStream()); final FileOutputStream target = new FileOutputStream(file)) {
        target.getChannel().transferFrom(source, 0, Long.MAX_VALUE);
      } catch (final IOException ex) {
        throw new CompletionException(ex);
      }
      return file;
    });

    download.whenCompleteAsync((value, exception) -> {
      await.remove(toIndexName(expansion));

      if (exception != null) {
        plugin.getLogger().log(Level.SEVERE,
            "failed to download " + expansion.getName() + ":" + version.getVersion(), exception);
      }
    });

    await.put(toIndexName(expansion), download);

    return download;
  }

}
