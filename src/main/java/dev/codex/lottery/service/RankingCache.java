package dev.codex.lottery.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class RankingCache {

    private final Map<String, Index> indices = new HashMap<>();

    public synchronized Index get(String key, Supplier<? extends Collection<Entry>> source) {
        return indices.computeIfAbsent(key, ignored -> {
            List<Entry> entries = source.get().stream().filter(entry -> entry.value() > 0)
                .sorted(Comparator.comparingDouble(Entry::value).reversed()).toList();
            Map<UUID, Integer> positions = new HashMap<>();
            for (int index = 0; index < entries.size(); index++) {
                positions.put(entries.get(index).id(), index + 1);
            }
            return new Index(entries, Map.copyOf(positions));
        });
    }

    public synchronized void invalidate(String key) {
        indices.remove(key);
    }

    public synchronized void clear() {
        indices.clear();
    }

    public record Entry(UUID id, String name, double value) { }

    public record Index(List<Entry> entries, Map<UUID, Integer> positions) {
        public int rank(UUID playerId) {
            return positions.getOrDefault(playerId, 0);
        }
    }
}
