package ton.dariushkmetsyak.ErrorHandling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Политика повторных попыток для операций, которые могут временно не срабатывать
 */
public class RetryPolicy {
    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);
    
    private final int maxAttempts;
    private final long initialDelayMs;
    private final double backoffMultiplier;
    private final long maxDelayMs;
    
    /**
     * Конструктор с настройками по умолчанию
     */
    public RetryPolicy() {
        this(3, 1000, 2.0, 30000);
    }
    
    /**
     * Конструктор с пользовательскими настройками
     * 
     * @param maxAttempts Максимальное количество попыток
     * @param initialDelayMs Начальная задержка в миллисекундах
     * @param backoffMultiplier Множитель для экспоненциальной задержки
     * @param maxDelayMs Максимальная задержка между попытками
     */
    public RetryPolicy(int maxAttempts, long initialDelayMs, double backoffMultiplier, long maxDelayMs) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelayMs = maxDelayMs;
    }
    
    /**
     * Выполнить операцию с повторными попытками
     * 
     * @param operation Операция для выполнения
     * @param operationName Название операции (для логирования)
     * @return Результат операции
     * @throws Exception Если все попытки неудачны
     */
    public <T> T execute(Supplier<T> operation, String operationName) throws Exception {
        Exception lastException = null;
        long delay = initialDelayMs;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.debug("Executing {}, attempt {}/{}", operationName, attempt, maxAttempts);
                T result = operation.get();
                
                if (attempt > 1) {
                    log.info("Operation {} succeeded on attempt {}", operationName, attempt);
                }
                
                return result;
                
            } catch (Exception e) {
                lastException = e;
                
                // Проверяем, является ли ошибка временной
                if (!ErrorHandler.isTransientError(e)) {
                    log.error("Non-transient error in {}, not retrying: {}",
                        operationName, e.getMessage());
                    throw e;
                }
                
                if (attempt < maxAttempts) {
                    log.warn("Attempt {}/{} failed for {}: {}. Retrying in {}ms...",
                        attempt, maxAttempts, operationName, e.getMessage(), delay);
                    
                    try {
                        TimeUnit.MILLISECONDS.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                    
                    // Экспоненциальное увеличение задержки
                    delay = Math.min((long)(delay * backoffMultiplier), maxDelayMs);
                    
                } else {
                    log.error("All {} attempts failed for {}",
                        maxAttempts, operationName);
                }
            }
        }
        
        // Все попытки неудачны
        throw new RetryExhaustedException(
            String.format("Failed after %d attempts: %s", maxAttempts, operationName),
            lastException
        );
    }
    
    /**
     * Выполнить операцию с повторными попытками (void операции)
     */
    public void executeVoid(Runnable operation, String operationName) throws Exception {
        execute(() -> {
            operation.run();
            return null;
        }, operationName);
    }
    
    /**
     * Создать политику для API вызовов (больше попыток, большие задержки)
     */
    public static RetryPolicy forApiCalls() {
        return new RetryPolicy(5, 2000, 2.0, 60000);
    }
    
    /**
     * Создать политику для файловых операций (меньше попыток)
     */
    public static RetryPolicy forFileOperations() {
        return new RetryPolicy(3, 500, 1.5, 5000);
    }
    
    /**
     * Создать политику для сетевых операций
     */
    public static RetryPolicy forNetworkOperations() {
        return new RetryPolicy(4, 1500, 2.0, 30000);
    }
    
    /**
     * Исключение, когда исчерпаны все попытки
     */
    public static class RetryExhaustedException extends Exception {
        public RetryExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
