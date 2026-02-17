package ton.dariushkmetsyak.Persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Менеджер для управления сохранением и восстановлением состояния торговли
 * Обеспечивает автоматическое сохранение и восстановление после сбоев
 */
public class StateManager {
    private static final Logger log = LoggerFactory.getLogger(StateManager.class);
    
    private static final String DEFAULT_STATE_DIR = "trading_states";
    private static final String STATE_FILE_EXTENSION = ".json";
    private static final int DEFAULT_AUTOSAVE_INTERVAL_SECONDS = 30;
    private static final int MAX_BACKUP_FILES = 10;
    
    private final String stateDirectory;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isRunning;
    private ScheduledFuture<?> autosaveTask;
    
    private TradingState currentState;
    private String currentStateFilePath;
    
    /**
     * Конструктор с директорией по умолчанию
     */
    public StateManager() {
        this(DEFAULT_STATE_DIR);
    }
    
    /**
     * Конструктор с указанием директории
     */
    public StateManager(String stateDirectory) {
        this.stateDirectory = stateDirectory;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.isRunning = new AtomicBoolean(false);
        
        // Создать директорию, если не существует
        createStateDirectory();
    }
    
    /**
     * Создать директорию для сохранения состояний
     */
    private void createStateDirectory() {
        try {
            Path path = Paths.get(stateDirectory);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("Created state directory: {}", stateDirectory);
            }
        } catch (IOException e) {
            log.error("Failed to create state directory: {}", stateDirectory, e);
        }
    }
    
    /**
     * Получить путь к файлу состояния для конкретной монеты
     */
    public String getStateFilePath(String coinName, String accountType) {
        String fileName = String.format("%s_%s%s", 
            coinName.toLowerCase(), 
            accountType.toLowerCase(),
            STATE_FILE_EXTENSION
        );
        return Paths.get(stateDirectory, fileName).toString();
    }
    
    /**
     * Сохранить состояние
     */
    public synchronized void saveState(TradingState state) {
        if (state == null) {
            log.warn("Attempted to save null state");
            return;
        }
        
        try {
            String filePath = getStateFilePath(state.getCoinName(), state.getAccountType());
            
            // Создать бэкап существующего файла
            if (new File(filePath).exists()) {
                createBackup(filePath);
            }
            
            // Сохранить состояние
            state.saveToFile(filePath);
            log.info("State saved successfully: {}", filePath);
            
            this.currentState = state;
            this.currentStateFilePath = filePath;
            
        } catch (IOException e) {
            log.error("Failed to save state for coin: {}", state.getCoinName(), e);
            notifyError("Ошибка сохранения состояния: " + e.getMessage());
        }
    }
    
    /**
     * Загрузить состояние
     */
    public TradingState loadState(String coinName, String accountType) {
        String filePath = getStateFilePath(coinName, accountType);
        
        if (!TradingState.stateFileExists(filePath)) {
            log.info("No saved state found for coin: {} ({})", coinName, accountType);
            return null;
        }
        
        try {
            TradingState state = TradingState.loadFromFile(filePath);
            log.info("State loaded successfully: {}", filePath);
            
            this.currentState = state;
            this.currentStateFilePath = filePath;
            
            return state;
            
        } catch (IOException e) {
            log.error("Failed to load state from: {}", filePath, e);
            notifyError("Ошибка загрузки состояния: " + e.getMessage());
            
            // Попытаться восстановить из бэкапа
            return attemptRestoreFromBackup(filePath);
        }
    }
    
    /**
     * Попытаться восстановить из бэкапа
     */
    private TradingState attemptRestoreFromBackup(String originalFilePath) {
        log.info("Attempting to restore from backup...");
        
        try {
            File stateDir = new File(stateDirectory);
            String baseName = new File(originalFilePath).getName();
            String backupPrefix = baseName + ".backup.";
            
            File[] backupFiles = stateDir.listFiles((dir, name) -> 
                name.startsWith(backupPrefix)
            );
            
            if (backupFiles == null || backupFiles.length == 0) {
                log.warn("No backup files found");
                return null;
            }
            
            // Сортировать по времени (самый новый первым)
            java.util.Arrays.sort(backupFiles, (a, b) -> 
                Long.compare(b.lastModified(), a.lastModified())
            );
            
            // Попытаться загрузить самый новый бэкап
            for (File backupFile : backupFiles) {
                try {
                    TradingState state = TradingState.loadFromFile(backupFile.getPath());
                    log.info("Successfully restored from backup: {}", backupFile.getName());
                    notifyError("Состояние восстановлено из бэкапа: " + backupFile.getName());
                    return state;
                } catch (IOException e) {
                    log.warn("Failed to load backup: {}", backupFile.getName(), e);
                }
            }
            
        } catch (Exception e) {
            log.error("Error during backup restoration", e);
        }
        
        return null;
    }
    
    /**
     * Создать бэкап файла состояния
     */
    private void createBackup(String filePath) {
        try {
            String backupPath = filePath + ".backup." + System.currentTimeMillis();
            Files.copy(Paths.get(filePath), Paths.get(backupPath));
            log.debug("Backup created: {}", backupPath);
            
            // Очистить старые бэкапы
            cleanupOldBackups(filePath);
            
        } catch (IOException e) {
            log.warn("Failed to create backup for: {}", filePath, e);
        }
    }
    
    /**
     * Удалить старые бэкапы, оставив только MAX_BACKUP_FILES
     */
    private void cleanupOldBackups(String originalFilePath) {
        try {
            File stateDir = new File(stateDirectory);
            String baseName = new File(originalFilePath).getName();
            String backupPrefix = baseName + ".backup.";
            
            File[] backupFiles = stateDir.listFiles((dir, name) -> 
                name.startsWith(backupPrefix)
            );
            
            if (backupFiles != null && backupFiles.length > MAX_BACKUP_FILES) {
                // Сортировать по времени
                java.util.Arrays.sort(backupFiles, (a, b) -> 
                    Long.compare(a.lastModified(), b.lastModified())
                );
                
                // Удалить старые
                int toDelete = backupFiles.length - MAX_BACKUP_FILES;
                for (int i = 0; i < toDelete; i++) {
                    if (backupFiles[i].delete()) {
                        log.debug("Deleted old backup: {}", backupFiles[i].getName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error during backup cleanup", e);
        }
    }
    
    /**
     * Запустить автоматическое сохранение
     */
    public void startAutosave(int intervalSeconds) {
        if (isRunning.get()) {
            log.warn("Autosave is already running");
            return;
        }
        
        isRunning.set(true);
        
        autosaveTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (currentState != null) {
                    saveState(currentState);
                    log.debug("Autosave completed");
                }
            } catch (Exception e) {
                log.error("Error during autosave", e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        
        log.info("Autosave started with interval: {} seconds", intervalSeconds);
    }
    
    /**
     * Запустить автоматическое сохранение с интервалом по умолчанию
     */
    public void startAutosave() {
        startAutosave(DEFAULT_AUTOSAVE_INTERVAL_SECONDS);
    }
    
    /**
     * Остановить автоматическое сохранение
     */
    public void stopAutosave() {
        if (!isRunning.get()) {
            return;
        }
        
        isRunning.set(false);
        
        if (autosaveTask != null) {
            autosaveTask.cancel(false);
        }
        
        // Финальное сохранение
        if (currentState != null) {
            saveState(currentState);
        }
        
        log.info("Autosave stopped");
    }
    
    /**
     * Завершить работу менеджера
     */
    public void shutdown() {
        stopAutosave();
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("StateManager shutdown completed");
    }
    
    /**
     * Получить текущее состояние
     */
    public TradingState getCurrentState() {
        return currentState;
    }
    
    /**
     * Установить текущее состояние
     */
    public void setCurrentState(TradingState state) {
        this.currentState = state;
    }
    
    /**
     * Удалить файл состояния
     */
    public boolean deleteState(String coinName, String accountType) {
        String filePath = getStateFilePath(coinName, accountType);
        File file = new File(filePath);
        
        if (file.exists()) {
            boolean deleted = file.delete();
            if (deleted) {
                log.info("State file deleted: {}", filePath);
            }
            return deleted;
        }
        
        return false;
    }
    
    /**
     * Отправить уведомление об ошибке
     */
    private void notifyError(String message) {
        try {
            ImageAndMessageSender.sendTelegramMessage("⚠️ " + message);
        } catch (Exception e) {
            log.warn("Failed to send error notification", e);
        }
    }
    
    /**
     * Проверить существование сохраненного состояния
     */
    public boolean hasState(String coinName, String accountType) {
        String filePath = getStateFilePath(coinName, accountType);
        return TradingState.stateFileExists(filePath);
    }
}
