package com.igrobar.createdynmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Чёрные списки станций и поездов (blacklist-stations.yml / blacklist-trains.yml).
 * Файлы проверяются один раз за цикл обновления (reloadIfChanged), а не на каждый маркер,
 * поэтому в главном потоке нет лишней работы с диском. Изменения подхватываются автоматически.
 */
final class BlacklistFiles {
    private final Path folder;
    private long stationStamp = Long.MIN_VALUE;
    private long trainStamp = Long.MIN_VALUE;
    private Set<String> stations = Set.of();
    private Set<String> trains = Set.of();

    BlacklistFiles(Path folder) {
        this.folder = folder;
    }

    void reloadIfChanged() {
        try {
            Path stationFile = this.folder.resolve("blacklist-stations.yml");
            Path trainFile = this.folder.resolve("blacklist-trains.yml");
            if (!Files.exists(stationFile) || !Files.exists(trainFile)) {
                Files.createDirectories(this.folder);
                ensureTemplate(stationFile, "stations");
                ensureTemplate(trainFile, "trains");
            }
            long stationTime = Files.getLastModifiedTime(stationFile).toMillis();
            if (stationTime != this.stationStamp) {
                this.stations = read(stationFile, "stations");
                this.stationStamp = stationTime;
            }
            long trainTime = Files.getLastModifiedTime(trainFile).toMillis();
            if (trainTime != this.trainStamp) {
                this.trains = read(trainFile, "trains");
                this.trainStamp = trainTime;
            }
        } catch (Throwable ignored) {
            // при ошибке чтения оставляем прежние списки
        }
    }

    boolean isBlacklisted(String markerId, String label) {
        Set<String> set = markerId != null && markerId.startsWith("train_") ? this.trains : this.stations;
        return contains(set, label) || contains(set, markerId);
    }

    private static void ensureTemplate(Path path, String section) throws IOException {
        if (!Files.exists(path)) {
            String template = "# Add exact names below. Changes are picked up automatically.\n" + section + ":\n  # - Example name\n";
            Files.writeString(path, template, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    private static Set<String> read(Path path, String section) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        boolean inSection = false;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!line.startsWith("-") && line.endsWith(":")) {
                inSection = line.substring(0, line.length() - 1).trim().equals(section);
                continue;
            }
            if (!inSection || !line.startsWith("-")) {
                continue;
            }
            String value = line.substring(1).trim();
            int comment = value.indexOf(" #");
            if (comment >= 0) {
                value = value.substring(0, comment).trim();
            }
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isBlank() || value.startsWith("Example name")) {
                continue;
            }
            result.add(value.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean contains(Set<String> set, String value) {
        return value != null && set.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
