package com.igrobar.createdynmap;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class BlacklistFiles {
    private static final Object LOCK = new Object();
    private static volatile long stationStamp = Long.MIN_VALUE;
    private static volatile long trainStamp = Long.MIN_VALUE;
    private static volatile Set<String> stations = Set.of();
    private static volatile Set<String> trains = Set.of();

    private BlacklistFiles() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static boolean isBlacklisted(Object object, String string, String string2) {
        try {
            Set<String> set;
            String string3 = string != null && string.startsWith("train_") ? "trains" : "stations";
            Path path = BlacklistFiles.dataFolder(object);
            if (path == null) {
                return false;
            }
            Files.createDirectories(path, new FileAttribute[0]);
            Path path2 = path.resolve(string3.equals("trains") ? "blacklist-trains.yml" : "blacklist-stations.yml");
            BlacklistFiles.ensureTemplate(path2, string3);
            long l = Files.getLastModifiedTime(path2, new LinkOption[0]).toMillis();
            Object object2 = LOCK;
            synchronized (object2) {
                if (string3.equals("trains") && l != trainStamp) {
                    trains = BlacklistFiles.read(path2, "trains");
                    trainStamp = l;
                }
                if (string3.equals("stations") && l != stationStamp) {
                    stations = BlacklistFiles.read(path2, "stations");
                    stationStamp = l;
                }
                set = string3.equals("trains") ? trains : stations;
            }
            return BlacklistFiles.contains(set, string2) || BlacklistFiles.contains(set, string);
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    private static Path dataFolder(Object object) throws Exception {
        Method method = object.getClass().getMethod("getDataFolder", new Class[0]);
        Object object2 = method.invoke(object, new Object[0]);
        return object2 instanceof File ? ((File)object2).toPath() : null;
    }

    private static void ensureTemplate(Path path, String string) throws IOException {
        if (!Files.exists(path, new LinkOption[0])) {
            String string2 = "# Add exact names below. Changes are picked up automatically.\n" + string + ":\n  # - Example name\n";
            Files.writeString(path, (CharSequence)string2, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    private static Set<String> read(Path path, String string) throws IOException {
        LinkedHashSet<String> linkedHashSet = new LinkedHashSet<String>();
        boolean bl = false;
        for (String string2 : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String string3 = string2.trim();
            if (string3.isEmpty() || string3.startsWith("#")) continue;
            if (!string3.startsWith("-") && string3.endsWith(":")) {
                bl = string3.substring(0, string3.length() - 1).trim().equals(string);
                continue;
            }
            if (!bl || !string3.startsWith("-")) continue;
            String string4 = string3.substring(1).trim();
            int n = string4.indexOf(" #");
            if (n >= 0) {
                string4 = string4.substring(0, n).trim();
            }
            if (string4.length() >= 2 && (string4.startsWith("\"") && string4.endsWith("\"") || string4.startsWith("'") && string4.endsWith("'"))) {
                string4 = string4.substring(1, string4.length() - 1);
            }
            if (string4.isBlank() || string4.startsWith("Example name")) continue;
            linkedHashSet.add(string4.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(linkedHashSet);
    }

    private static boolean contains(Set<String> set, String string) {
        return string != null && set.contains(string.trim().toLowerCase(Locale.ROOT));
    }
}

