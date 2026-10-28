package dev.codex.lottery.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Durable deltas on the calling thread, detached full checkpoints on one writer. */
public final class JournaledYamlStorage implements AutoCloseable {

    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    private final Map<String, Path> files;
    private final Map<String, Map<String, Object>> documents = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> checkpointed = new LinkedHashMap<>();
    private final BiConsumer<String, YamlConfiguration> mirror;
    private final Logger logger;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Craftplay-Lotterie-Storage");
        thread.setDaemon(true);
        return thread;
    });
    private final FileChannel journal;
    private final FileLock journalLock;
    private ScheduledFuture<?> scheduled;
    private long generation;
    private long retryDelayMillis = 250;
    private boolean closed;

    public JournaledYamlStorage(Path folder, Map<String, Path> files,
                               BiConsumer<String, YamlConfiguration> mirror, Logger logger) throws IOException {
        this.files = Map.copyOf(files);
        this.mirror = mirror;
        this.logger = logger;
        Files.createDirectories(folder);
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            YamlConfiguration config = new YamlConfiguration();
            if (Files.exists(entry.getValue())) {
                try {
                    config.load(entry.getValue().toFile());
                } catch (InvalidConfigurationException exception) {
                    throw new IOException("Invalid storage file: " + entry.getValue(), exception);
                }
            }
            documents.put(entry.getKey(), snapshot(config));
        }
        journal = FileChannel.open(folder.resolve("persistence.wal"), StandardOpenOption.CREATE,
            StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            journalLock = journal.tryLock();
            if (journalLock == null) {
                throw new IOException("Lottery storage is already open in another process");
            }
            recover();
        } catch (IOException | RuntimeException exception) {
            journal.close();
            writer.shutdownNow();
            throw exception;
        }
    }

    public synchronized YamlConfiguration read(String name) {
        return configuration(documents.getOrDefault(name, Map.of()));
    }

    public synchronized Map<String, Map<String, Object>> capture() {
        return Map.copyOf(documents);
    }

    public static String serializeSnapshot(Map<String, Object> snapshot) {
        return configuration(snapshot).saveToString();
    }

    public static Map<String, Object> snapshot(ConfigurationSection config) {
        Map<String, Object> flat = new LinkedHashMap<>();
        config.getValues(true).forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection)) {
                flat.put(key, freeze(value));
            }
        });
        return Collections.unmodifiableMap(flat);
    }

    private static Object freeze(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(JournaledYamlStorage::freeze).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(key, freeze(entry)));
            return Collections.unmodifiableMap(copy);
        }
        return value;
    }

    public synchronized void commit(Map<String, Map<String, Object>> snapshots) throws IOException {
        if (closed) {
            throw new IOException("Lottery storage is closed");
        }
        YamlConfiguration delta = new YamlConfiguration();
        for (Map.Entry<String, Map<String, Object>> document : snapshots.entrySet()) {
            if (!files.containsKey(document.getKey())) {
                throw new IllegalArgumentException("Unknown storage document: " + document.getKey());
            }
            Map<String, Object> previous = documents.get(document.getKey());
            Map<String, Object> updates = new LinkedHashMap<>();
            document.getValue().forEach((key, value) -> {
                if (!Objects.equals(previous.get(key), value)) {
                    updates.put(key, value);
                }
            });
            List<String> removed = previous.keySet().stream().filter(key -> !document.getValue().containsKey(key)).toList();
            if (!updates.isEmpty() || !removed.isEmpty()) {
                delta.set(document.getKey() + ".updates", updates);
                delta.set(document.getKey() + ".removed", removed);
            }
        }
        if (delta.getKeys(false).isEmpty()) {
            return;
        }

        byte[] payload = delta.saveToString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_RECORD_BYTES) {
            throw new IOException("Lottery storage delta exceeds journal record limit");
        }
        CRC32 checksum = new CRC32();
        checksum.update(payload);
        ByteBuffer record = ByteBuffer.allocate(8 + payload.length);
        record.putInt(payload.length).putInt((int) checksum.getValue()).put(payload).flip();
        long position = journal.size();
        try {
            journal.position(position);
            while (record.hasRemaining()) {
                journal.write(record);
            }
            journal.force(true);
        } catch (IOException exception) {
            journal.truncate(position);
            throw exception;
        }
        // Only publish the new baseline after the complete record is durable.
        snapshots.forEach((name, state) -> documents.put(name, state));
        generation++;
        scheduleCheckpoint();
    }

    private synchronized void scheduleCheckpoint() {
        if (scheduled == null && !writer.isShutdown()) {
            scheduled = writer.schedule(() -> {
                synchronized (this) {
                    scheduled = null;
                }
                try {
                    checkpoint();
                    synchronized (this) {
                        retryDelayMillis = 250;
                    }
                } catch (IOException | RuntimeException exception) {
                    logger.log(Level.SEVERE, "Lottery checkpoint failed; durable journal retained", exception);
                    synchronized (this) {
                        retryDelayMillis = Math.min(30_000, retryDelayMillis * 2);
                        scheduleCheckpoint();
                    }
                }
            }, retryDelayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void checkpoint() throws IOException {
        Map<String, Map<String, Object>> state;
        long version;
        synchronized (this) {
            state = new LinkedHashMap<>(documents);
            version = generation;
        }
        for (Map.Entry<String, Map<String, Object>> document : state.entrySet()) {
            if (document.getValue().equals(checkpointed.get(document.getKey()))) {
                continue;
            }
            YamlConfiguration config = configuration(document.getValue());
            writeAtomic(files.get(document.getKey()), config.saveToString());
            mirror.accept(document.getKey(), config);
            checkpointed.put(document.getKey(), document.getValue());
        }
        synchronized (this) {
            if (version == generation) {
                journal.truncate(0);
                journal.position(0);
                journal.force(true);
            } else {
                scheduleCheckpoint();
            }
        }
    }

    public void flush() {
        synchronized (this) {
            if (closed) {
                return;
            }
            if (scheduled != null) {
                scheduled.cancel(false);
                scheduled = null;
            }
        }
        try {
            writer.submit(() -> {
                checkpoint();
                return null;
            }).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while flushing lottery storage", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not flush lottery storage; journal retained", exception.getCause());
        }
    }

    public void executeAfterFlush(Runnable task) {
        executeAfterFlush(task, exception -> logger.log(Level.SEVERE, "Lottery storage operation failed; journal retained", exception));
    }

    public void executeAfterFlush(Runnable task, Consumer<Throwable> failure) {
        writer.execute(() -> {
            try {
                checkpoint();
                task.run();
            } catch (IOException | RuntimeException exception) {
                failure.accept(exception);
            }
        });
    }

    public void execute(Runnable task, Consumer<Throwable> failure) {
        writer.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException exception) {
                failure.accept(exception);
            }
        });
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        try {
            flush();
        } finally {
            synchronized (this) {
                writer.shutdown();
                if (scheduled != null) {
                    scheduled.cancel(false);
                    scheduled = null;
                }
            }
            try {
                if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Lottery storage writer did not stop");
                }
                journal.close();
                synchronized (this) {
                    closed = true;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing lottery storage", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not close lottery journal", exception);
            }
        }
    }

    private void recover() throws IOException {
        long validBytes = 0;
        while (journal.position() < journal.size()) {
            ByteBuffer header = ByteBuffer.allocate(8);
            if (!readFully(header)) {
                break;
            }
            header.flip();
            int length = header.getInt();
            int expected = header.getInt();
            if (length < 0 || length > MAX_RECORD_BYTES) {
                throw new IOException("Invalid lottery journal record at " + validBytes);
            }
            ByteBuffer payload = ByteBuffer.allocate(length);
            if (!readFully(payload)) {
                break;
            }
            CRC32 checksum = new CRC32();
            checksum.update(payload.array());
            if ((int) checksum.getValue() != expected) {
                throw new IOException("Corrupt lottery journal record at " + validBytes);
            }
            YamlConfiguration delta = new YamlConfiguration();
            try {
                delta.loadFromString(new String(payload.array(), StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException exception) {
                throw new IOException("Invalid lottery journal payload", exception);
            }
            for (String name : delta.getKeys(false)) {
                if (!files.containsKey(name)) {
                    throw new IOException("Unknown document in lottery journal: " + name);
                }
                Map<String, Object> state = new LinkedHashMap<>(documents.get(name));
                delta.getStringList(name + ".removed").forEach(state::remove);
                ConfigurationSection updates = delta.getConfigurationSection(name + ".updates");
                if (updates != null) {
                    state.putAll(snapshot(updates));
                }
                documents.put(name, Collections.unmodifiableMap(state));
            }
            validBytes = journal.position();
        }
        // A crash may leave an incomplete final record, but never discard a complete corrupt record.
        journal.truncate(validBytes);
        journal.position(validBytes);
    }

    private boolean readFully(ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            if (journal.read(target) < 0) {
                return false;
            }
        }
        return true;
    }

    private static YamlConfiguration configuration(Map<String, Object> flat) {
        YamlConfiguration config = new YamlConfiguration();
        flat.forEach(config::set);
        return config;
    }

    public static void writeAtomic(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
