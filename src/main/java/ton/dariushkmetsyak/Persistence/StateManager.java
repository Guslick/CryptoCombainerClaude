package ton.dariushkmetsyak.Persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Менеджер сохранения/восстановления состояния трейдера.
 * Файл состояния именуется по sessionId: trading_states/<sessionId>.json
 * Бэкапы: trading_states/<sessionId>.json.bak.<timestamp>
 */
public class StateManager {
    private static final Logger log = LoggerFactory.getLogger(StateManager.class);

    private static final String STATE_DIR = "trading_states";
    private static final int DEFAULT_AUTOSAVE_SEC = 15;
    private static final int MAX_BACKUPS = 5;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledFuture<?> autosaveTask;
    private volatile TradingState currentState;

    public StateManager() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StateManager-autosave");
            t.setDaemon(true);
            return t;
        });
        try { Files.createDirectories(Paths.get(STATE_DIR)); } catch (IOException ignored) {}
    }

    // ---- Path helpers ----

    /** Primary path for a sessionId */
    public static String getPath(String sessionId) {
        return Paths.get(STATE_DIR, sessionId + ".json").toString();
    }

    // ---- Save / Load ----

    public synchronized void saveState(TradingState state) {
        if (state == null) return;
        try {
            String path = getPath(state.getSessionId());
            // Rotate backups
            File existing = new File(path);
            if (existing.exists()) rotateBackups(path);
            state.saveToFile(path);
            this.currentState = state;
            log.debug("State saved: {}", path);
        } catch (IOException e) {
            log.error("Failed to save state for session {}", state.getSessionId(), e);
        }
    }

    public TradingState loadState(String sessionId) {
        String path = getPath(sessionId);
        if (!new File(path).exists()) return null;
        try {
            TradingState state = TradingState.loadFromFile(path);
            this.currentState = state;
            log.info("State loaded: {}", path);
            return state;
        } catch (IOException e) {
            log.warn("Failed to load state {}, trying backups...", path);
            return restoreFromBackup(path);
        }
    }

    public boolean hasState(String sessionId) {
        return new File(getPath(sessionId)).exists();
    }

    // ---- Backward-compat overloads (used by MenuHandler) ----

    /**
     * Backward-compat: delete by coinName + accountType (old naming scheme).
     * Tries both old path (coinName_accountType.json) and new (sessionId.json via scan).
     */
    public void deleteState(String coinName, String accountType) {
        // Old naming scheme: trading_states/bitcoin_testeraccount.json
        String oldPath = Paths.get(STATE_DIR,
                coinName.toLowerCase() + "_" + accountType.toLowerCase() + ".json").toString();
        new File(oldPath).delete();
        // Also scan for any new-style file matching this coin+type
        File dir = new File(STATE_DIR);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !n.contains(".bak."));
        if (files != null) {
            for (File f : files) {
                try {
                    TradingState s = TradingState.loadFromFile(f.getPath());
                    if (coinName.equalsIgnoreCase(s.getCoinName())
                            && accountType.equalsIgnoreCase(s.getAccountType())) {
                        deleteState(f.getName().replace(".json", ""));
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Backward-compat: find any state for the given accountType.
     * Returns the most recently saved state not older than 24h.
     */
    public TradingState findAnyState(String accountType) {
        File dir = new File(STATE_DIR);
        if (!dir.exists()) return null;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !n.contains(".bak."));
        if (files == null) return null;
        TradingState best = null;
        long bestTs = 0;
        for (File f : files) {
            try {
                TradingState s = TradingState.loadFromFile(f.getPath());
                if (accountType != null && !accountType.equalsIgnoreCase(s.getAccountType())) continue;
                long age = System.currentTimeMillis() - s.getTimestamp();
                if (age > java.util.concurrent.TimeUnit.HOURS.toMillis(24)) continue;
                if (s.getTimestamp() > bestTs) { bestTs = s.getTimestamp(); best = s; }
            } catch (Exception ignored) {}
        }
        return best;
    }

    public void deleteState(String sessionId) {
        String path = getPath(sessionId);
        new File(path).delete();
        // also delete backups
        File dir = new File(STATE_DIR);
        String prefix = sessionId + ".json.bak.";
        File[] baks = dir.listFiles((d, n) -> n.startsWith(prefix));
        if (baks != null) for (File f : baks) f.delete();
        log.info("State deleted for session {}", sessionId);
    }

    // ---- Backup rotation ----

    private void rotateBackups(String path) {
        try {
            String bakPath = path + ".bak." + System.currentTimeMillis();
            Files.copy(Paths.get(path), Paths.get(bakPath));
            // Prune old backups
            File dir = new File(STATE_DIR);
            String prefix = new File(path).getName() + ".bak.";
            File[] baks = dir.listFiles((d, n) -> n.startsWith(prefix));
            if (baks != null && baks.length > MAX_BACKUPS) {
                java.util.Arrays.sort(baks, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < baks.length - MAX_BACKUPS; i++) baks[i].delete();
            }
        } catch (IOException ignored) {}
    }

    private TradingState restoreFromBackup(String originalPath) {
        File dir = new File(STATE_DIR);
        String prefix = new File(originalPath).getName() + ".bak.";
        File[] baks = dir.listFiles((d, n) -> n.startsWith(prefix));
        if (baks == null || baks.length == 0) return null;
        java.util.Arrays.sort(baks, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File bak : baks) {
            try {
                TradingState state = TradingState.loadFromFile(bak.getPath());
                log.info("Restored from backup: {}", bak.getName());
                return state;
            } catch (IOException ignored) {}
        }
        return null;
    }

    // ---- Autosave ----

    public void startAutosave() {
        if (!running.compareAndSet(false, true)) return;
        autosaveTask = scheduler.scheduleAtFixedRate(() -> {
            TradingState s = currentState;
            if (s != null) saveState(s);
        }, DEFAULT_AUTOSAVE_SEC, DEFAULT_AUTOSAVE_SEC, TimeUnit.SECONDS);
        log.info("Autosave started ({}s interval)", DEFAULT_AUTOSAVE_SEC);
    }

    public void setCurrentState(TradingState state) { this.currentState = state; }
    public TradingState getCurrentState() { return currentState; }

    public void shutdown() {
        running.set(false);
        if (autosaveTask != null) autosaveTask.cancel(false);
        // Final save
        TradingState s = currentState;
        if (s != null) saveState(s);
        scheduler.shutdown();
        try { scheduler.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        log.info("StateManager shutdown");
    }
}
