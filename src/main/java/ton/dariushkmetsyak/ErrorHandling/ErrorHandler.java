package ton.dariushkmetsyak.ErrorHandling;

import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Централизованный обработчик ошибок
 * Гарантирует, что программа не вылетит, а попытается восстановиться
 */
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * Обработка критической ошибки с уведомлением пользователя
     * 
     * @param e Исключение
     * @param context Контекст ошибки (где произошла)
     * @param action Что делала программа
     * @param shouldRecover Можно ли восстановиться
     * @return true если можно продолжить работу
     */
    public static boolean handleError(Exception e, String context, String action, boolean shouldRecover) {
        String timestamp = LocalDateTime.now().format(formatter);
        
        // Формирование детального сообщения
        String errorMessage = buildErrorMessage(e, context, action, timestamp);
        
        // Логирование
        log.error("Error in {}: {}", context, e.getMessage(), e);
        
        // Уведомление пользователя
        try {
            ImageAndMessageSender.sendTelegramMessage(errorMessage);
        } catch (Exception telegramError) {
            log.error("Failed to send Telegram notification", telegramError);
        }
        
        return shouldRecover;
    }
    
    /**
     * Обработка некритической ошибки (warning)
     */
    public static void handleWarning(Exception e, String context, String action) {
        log.warn("Warning in {}: {} - {}", context, action, e.getMessage());
        
        String warningMessage = String.format(
            "⚠️ Предупреждение\n\n" +
            "Контекст: %s\n" +
            "Действие: %s\n" +
            "Ошибка: %s\n\n" +
            "Программа продолжает работу.",
            context, action, e.getMessage()
        );
        
        try {
            ImageAndMessageSender.sendTelegramMessage(warningMessage);
        } catch (Exception ignored) {
            // Если не удалось отправить предупреждение - не критично
        }
    }
    
    /**
     * Обработка фатальной ошибки (программа должна остановиться)
     */
    public static void handleFatalError(Exception e, String context, String action) {
        String timestamp = LocalDateTime.now().format(formatter);
        
        String fatalMessage = String.format(
            "🛑 КРИТИЧЕСКАЯ ОШИБКА\n\n" +
            "⏰ Время: %s\n" +
            "📍 Контекст: %s\n" +
            "⚙️ Действие: %s\n\n" +
            "❌ Ошибка: %s\n\n" +
            "📝 Тип: %s\n" +
            "📄 Файл: %s\n\n" +
            "⛔ Программа остановлена.\n" +
            "Требуется перезапуск.",
            timestamp,
            context,
            action,
            e.getMessage(),
            e.getClass().getSimpleName(),
            getErrorLocation(e)
        );
        
        log.error("FATAL ERROR in {}: {}", context, e.getMessage(), e);
        
        try {
            ImageAndMessageSender.sendTelegramMessage(fatalMessage);
        } catch (Exception ignored) {
            // Последняя попытка уведомить
        }
        
        // Сохранение в файл
        saveErrorToFile(e, context, action, timestamp);
    }
    
    /**
     * Построение детального сообщения об ошибке
     */
    private static String buildErrorMessage(Exception e, String context, String action, String timestamp) {
        String stackTrace = getStackTraceString(e);
        String location = getErrorLocation(e);
        
        return String.format(
            "❌ ОШИБКА\n\n" +
            "⏰ Время: %s\n" +
            "📍 Контекст: %s\n" +
            "⚙️ Действие: %s\n\n" +
            "💥 Ошибка: %s\n" +
            "📝 Тип: %s\n" +
            "📄 Место: %s\n\n" +
            "🔄 Программа пытается восстановиться...",
            timestamp,
            context,
            action,
            e.getMessage() != null ? e.getMessage() : "Неизвестная ошибка",
            e.getClass().getSimpleName(),
            location
        );
    }
    
    /**
     * Получение местоположения ошибки в коде
     */
    private static String getErrorLocation(Exception e) {
        StackTraceElement[] trace = e.getStackTrace();
        if (trace != null && trace.length > 0) {
            StackTraceElement element = trace[0];
            return String.format("%s.%s():%d",
                element.getClassName().substring(element.getClassName().lastIndexOf('.') + 1),
                element.getMethodName(),
                element.getLineNumber()
            );
        }
        return "Неизвестно";
    }
    
    /**
     * Преобразование stack trace в строку
     */
    private static String getStackTraceString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
    
    /**
     * Сохранение ошибки в файл для дальнейшего анализа
     */
    private static void saveErrorToFile(Exception e, String context, String action, String timestamp) {
        try {
            String fileName = "logs/error_" + timestamp.replaceAll("[: ]", "_") + ".log";
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("logs"));
            
            String content = String.format(
                "Timestamp: %s\n" +
                "Context: %s\n" +
                "Action: %s\n" +
                "Exception: %s\n" +
                "Message: %s\n\n" +
                "Stack Trace:\n%s",
                timestamp, context, action,
                e.getClass().getName(),
                e.getMessage(),
                getStackTraceString(e)
            );
            
            java.nio.file.Files.write(java.nio.file.Paths.get(fileName), content.getBytes());
            log.info("Error saved to file: {}", fileName);
        } catch (Exception fileError) {
            log.error("Failed to save error to file", fileError);
        }
    }
    
    /**
     * Проверка, является ли ошибка временной (можно повторить)
     */
    public static boolean isTransientError(Exception e) {
        String message = e.getMessage();
        if (message == null) return false;
        
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("timeout") ||
               lowerMessage.contains("connection") ||
               lowerMessage.contains("network") ||
               lowerMessage.contains("temporarily") ||
               lowerMessage.contains("unavailable") ||
               lowerMessage.contains("rate limit") ||
               e instanceof java.net.SocketTimeoutException ||
               e instanceof java.net.ConnectException ||
               e instanceof java.io.IOException;
    }
    
    /**
     * Форматирование исключения для логов
     */
    public static String formatException(Exception e) {
        return String.format("[%s] %s at %s",
            e.getClass().getSimpleName(),
            e.getMessage(),
            getErrorLocation(e)
        );
    }
}
