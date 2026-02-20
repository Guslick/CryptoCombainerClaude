package ton.dariushkmetsyak.Telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Безопасная обёртка для ImageAndMessageSender
 * Гарантирует, что отправка сообщений никогда не прервёт работу программы
 */
public class SafeTelegramNotifier {
    private static final Logger log = LoggerFactory.getLogger(SafeTelegramNotifier.class);
    private static int consecutiveFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 10;
    
    /**
     * Безопасная отправка текстового сообщения
     * Никогда не бросает исключения
     */
    public static void sendMessage(String message) {
        try {
            ImageAndMessageSender.sendTelegramMessage(message);
            consecutiveFailures = 0; // Сброс при успехе
        } catch (Exception e) {
            handleSendFailure("sendMessage", message, e);
        }
    }
    
    /**
     * Безопасная отправка текстового сообщения с указанием chatId
     */
    public static void sendMessage(String message, long chatId) {
        try {
            ImageAndMessageSender.sendTelegramMessage(message, chatId);
            consecutiveFailures = 0;
        } catch (Exception e) {
            handleSendFailure("sendMessage(chatId)", message, e);
        }
    }
    
    /**
     * Безопасная отправка фото
     * Возвращает messageId или 0 при ошибке
     */
    public static int sendPhoto(String filePath, String caption) {
        try {
            int messageId = ImageAndMessageSender.sendPhoto(filePath, caption);
            consecutiveFailures = 0;
            return messageId;
        } catch (Exception e) {
            handleSendFailure("sendPhoto", caption, e);
            return 0;
        }
    }
    
    /**
     * Безопасная отправка фото с указанием chatId
     */
    public static void sendPhoto(String filePath, String caption, long chatId) {
        try {
            ImageAndMessageSender.sendPhoto(filePath, caption, chatId);
            consecutiveFailures = 0;
        } catch (Exception e) {
            handleSendFailure("sendPhoto(chatId)", caption, e);
        }
    }
    
    /**
     * Обработка ошибки отправки
     */
    private static void handleSendFailure(String method, String content, Exception e) {
        consecutiveFailures++;
        
        log.error("Failed to send Telegram notification via {}: {}", 
            method, e.getMessage());
        log.debug("Failed content (first 100 chars): {}", 
            content != null && content.length() > 100 ? 
                content.substring(0, 100) + "..." : content);
        
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            log.error("WARNING: {} consecutive Telegram send failures! " +
                "Telegram notifications may be unavailable.", 
                consecutiveFailures);
        }
        
        // НЕ бросаем исключение - программа должна продолжить работу
    }
    
    /**
     * Проверка доступности Telegram API
     */
    public static boolean isTelegramAvailable() {
        return consecutiveFailures < MAX_CONSECUTIVE_FAILURES;
    }
    
    /**
     * Получить количество последовательных ошибок
     */
    public static int getConsecutiveFailures() {
        return consecutiveFailures;
    }
    
    /**
     * Сброс счётчика ошибок (для тестирования)
     */
    public static void resetFailureCounter() {
        consecutiveFailures = 0;
    }
}
