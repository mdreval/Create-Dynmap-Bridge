package com.igrobar.createdynmap;

import java.util.Locale;
import java.util.Map;

/**
 * Plugin messages in English and Russian.
 * The language is chosen with {@code language} in config.yml ("en" or "ru"); it also sets
 * the language of the web map.
 */
final class Lang {
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("no-permission", "You do not have permission to use this command."),
            Map.entry("stations-file-missing", "The station list has not been created yet."),
            Map.entry("stations-header", "=== Create stations (%d) ==="),
            Map.entry("stations-read-error", "Could not read the station list: %s"),
            Map.entry("trains-file-missing", "The train list has not been created yet."),
            Map.entry("trains-header", "=== Create trains (%d) ==="),
            Map.entry("trains-read-error", "Could not read the train list: %s"),
            Map.entry("map-disabled", "The live train map is disabled."),
            Map.entry("map-link", "Train map: %s"),
            Map.entry("map-viewers", "Viewers right now: %d"),
            Map.entry("default-train-name", "Train"),
            Map.entry("default-station-name", "Station"));

    private static final Map<String, String> RU = Map.ofEntries(
            Map.entry("no-permission", "У вас нет прав на выполнение этой команды."),
            Map.entry("stations-file-missing", "Список станций ещё не создан."),
            Map.entry("stations-header", "=== Станции Create (%d) ==="),
            Map.entry("stations-read-error", "Не удалось прочитать список станций: %s"),
            Map.entry("trains-file-missing", "Список поездов ещё не создан."),
            Map.entry("trains-header", "=== Поезда Create (%d) ==="),
            Map.entry("trains-read-error", "Не удалось прочитать список поездов: %s"),
            Map.entry("map-disabled", "Веб-карта поездов выключена."),
            Map.entry("map-link", "Карта поездов: %s"),
            Map.entry("map-viewers", "Сейчас карту смотрят: %d"),
            Map.entry("default-train-name", "Поезд"),
            Map.entry("default-station-name", "Станция"));

    private final String code;
    private final Map<String, String> messages;

    private Lang(String code, Map<String, String> messages) {
        this.code = code;
        this.messages = messages;
    }

    /** "ru"/"russian" → Russian; everything else ("en", "uk", "gb", "us", empty) → English. */
    static Lang of(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("ru") || normalized.startsWith("ru_") || normalized.startsWith("ru-") || normalized.equals("russian")) {
            return new Lang("ru", RU);
        }
        return new Lang("en", EN);
    }

    /** Language code for the website: "en" or "ru". */
    String code() {
        return this.code;
    }

    String get(String key, Object... args) {
        String text = this.messages.getOrDefault(key, EN.getOrDefault(key, key));
        return args.length == 0 ? text : String.format(Locale.ROOT, text, args);
    }
}
