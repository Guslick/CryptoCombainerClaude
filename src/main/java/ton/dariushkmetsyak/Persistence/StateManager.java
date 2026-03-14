package ton.dariushkmetsyak.Persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-user state manager.
 * Files are stored in: trading_states/user_<userId>/<sessionId>.json
 * If userId == 0 (legacy), uses: trading_states/global/<sessionId>.json
 */
public class StateManager {
    private static final Logger log = LoggerFactory.getLogger(StateManager.class);

    private static final int    DEFAULT_AUTOSAVE_SEC = 15;
    private static final int    MAX_BACKUPS          = 5;

    private final String baseDir;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledFuture<?> autosaveTask;
    private volatile TradingState currentState;

    /** Create with user-scoped storage: trading_states/user_<userId>/ */
    public StateManager(long userId) {
        this(userId == 0 ? "trading_states/global" : "trading_states/user_" + userId);
    }

    /** Legacy constructor — uses trading_states/global/ */
    public StateManager() {
        this(0L);
    }

    private StateManager(String baseDir) {
        this.baseDir = baseDir;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StateManager-autosave");
            t.setDaemon(true);
            return t;
        });
        try { Files.createDirectories(Paths.get(baseDir)); } catch (IOException ignored) {}
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    public String getPath(String sessionId) {
        return Paths.get(baseDir, sessionId + ".json").toString();
    }

    // ── Save / Load ───────────────────────────────────────────────────────────

    public synchronized void saveState(TradingState state) {
        if (state == null) return;
        try {
            String path = getStatePath(state.getSessionId());
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
        String path = getStatePath(sessionId);
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
        return new File(getStatePath(sessionId)).exists();
    }

    public void deleteState(String sessionId) {
        String path = getStatePath(sessionId);
        new File(path).delete();
        File dir = new File(baseDir);
        String prefix = sessionId + ".json.bak.";
        File[] baks = dir.listFiles((d, n) -> n.startsWith(prefix));
        if (baks != null) for (File f : baks) f.delete();
        log.info("State deleted: {}", path);
    }

    // ── Backup rotation ───────────────────────────────────────────────────────

    private void rotateBackups(String path) {
        try {
            Files.copy(Paths.get(path), Paths.get(path + ".bak." + System.currentTimeMillis()));
            File dir = new File(baseDir);
            String prefix = new File(path).getName() + ".bak.";
            File[] baks = dir.listFiles((d, n) -> n.startsWith(prefix));
            if (baks != null && baks.length > MAX_BACKUPS) {
                java.util.Arrays.sort(baks, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < baks.length - MAX_BACKUPS; i++) baks[i].delete();
            }
        } catch (IOException ignored) {}
    }

    private TradingState restoreFromBackup(String originalPath) {
        File dir = new File(baseDir);
        String prefix = new File(originalPath).getName() + ".bak.";
        File[] baks = dir.listFiles((d, n) -> n.startsWith(prefix));
        if (baks == null || baks.length == 0) return null;
        java.util.Arrays.sort(baks, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File bak : baks) {
            try {
                TradingState s = TradingState.loadFromFile(bak.getPath());
                log.info("Restored from backup: {}", bak.getName());
                return s;
            } catch (IOException ignored) {}
        }
        return null;
    }

    // ── Autosave ──────────────────────────────────────────────────────────────

    public void startAutosave() {
        if (!running.compareAndSet(false, true)) return;
        autosaveTask = scheduler.scheduleAtFixedRate(() -> {
            TradingState s = currentState;
            if (s != null) saveState(s);
        }, DEFAULT_AUTOSAVE_SEC, DEFAULT_AUTOSAVE_SEC, TimeUnit.SECONDS);
        log.info("Autosave started ({}s, dir={})", DEFAULT_AUTOSAVE_SEC, baseDir);
    }

    public void setCurrentState(TradingState state) { this.currentState = state; }
    public TradingState getCurrentState() { return currentState; }

    public void shutdown() {
        running.set(false);
        if (autosaveTask != null) autosaveTask.cancel(false);
        TradingState s = currentState;
        if (s != null) saveState(s);
        scheduler.shutdown();
        try { scheduler.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }
}
