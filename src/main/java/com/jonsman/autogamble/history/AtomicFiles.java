package com.jonsman.autogamble.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

public final class AtomicFiles {
    @FunctionalInterface public interface WriterAction { void write(java.io.Writer writer) throws IOException; }
    private AtomicFiles() {}
    public static void write(Path file, String text) throws IOException {
        write(file, writer -> writer.write(text));
    }
    public static void write(Path file, WriterAction action) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { action.write(writer); }
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    public static void backup(Path file) throws IOException {
        if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName()+".corrupt-"+UUID.randomUUID()));
    }
}
