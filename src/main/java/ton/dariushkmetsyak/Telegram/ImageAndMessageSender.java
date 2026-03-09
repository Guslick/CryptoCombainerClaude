package ton.dariushkmetsyak.Telegram;

import org.json.JSONObject;
import ton.dariushkmetsyak.Config.AppConfig;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Утилита для отправки сообщений и фото в Telegram.
 * Токен берётся из AppConfig (config.properties или env-переменная TELEGRAM_BOT_TOKEN).
 * Если токен не задан — все методы тихо ничего не делают (не крашат торговые потоки).
 */
public class ImageAndMessageSender {

    // Токен загружается из конфига при первом обращении, не в static-инициализаторе
    private static volatile String BOT_TOKEN = null;
    private static String chatId;

    private static String getToken() {
        if (BOT_TOKEN == null) {
            BOT_TOKEN = AppConfig.getInstance().getBotToken();
        }
        return BOT_TOKEN;
    }

    /** Возвращает false если токен не задан — тогда отправка пропускается без краша */
    private static boolean isConfigured() {
        String token = getToken();
        return token != null && !token.isBlank() && !token.equals("YOUR_BOT_TOKEN_HERE");
    }

    public static void setChatId(long chatId) {
        ImageAndMessageSender.chatId = String.valueOf(chatId);
    }

    public static void sendPhoto(String filePath, String caption, long chatId) {
        setChatId(chatId);
        sendPhoto(filePath, caption);
    }

    public static int sendPhoto(String filePath, String caption) {
        if (!isConfigured() || chatId == null) return 0;
        try {
            String apiUrl = "https://api.telegram.org/bot" + getToken() + "/sendPhoto";
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=boundary");

            OutputStream outputStream = conn.getOutputStream();
            outputStream.write(("--boundary\r\n").getBytes());
            outputStream.write(("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n").getBytes());
            outputStream.write((chatId + "\r\n").getBytes());

            if (caption != null) {
                outputStream.write(("--boundary\r\n").getBytes());
                outputStream.write(("Content-Disposition: form-data; name=\"caption\"\r\n\r\n").getBytes());
                outputStream.write((caption + "\r\n").getBytes());
            }

            var file = new File(filePath);
            FileInputStream fileInputStream = new FileInputStream(file);
            outputStream.write(("--boundary\r\n").getBytes());
            outputStream.write(("Content-Disposition: form-data; name=\"photo\"; filename=\"" + file.getName() + "\"\r\n").getBytes());
            outputStream.write(("Content-Type: image/jpeg\r\n\r\n").getBytes());

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            fileInputStream.close();
            outputStream.write(("\r\n--boundary--\r\n").getBytes());
            outputStream.flush();
            outputStream.close();

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream responseStream = conn.getInputStream();
                String response = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
                JSONObject jsonResponse = new JSONObject(response);
                return jsonResponse.getJSONObject("result").getInt("message_id");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static void sendTelegramMessage(String message, long chatId) {
        setChatId(chatId);
        sendTelegramMessage(message);
    }

    public static void deleteMessage(int messageID) {
        if (!isConfigured() || chatId == null) return;
        try {
            String deleteUrlString = "https://api.telegram.org/bot" + getToken() + "/deleteMessage";
            URL deleteUrl = new URL(deleteUrlString);
            HttpURLConnection deleteConnection = (HttpURLConnection) deleteUrl.openConnection();
            deleteConnection.setRequestMethod("POST");
            deleteConnection.setRequestProperty("Content-Type", "application/json; utf-8");
            deleteConnection.setDoOutput(true);
            String deleteJson = "{\"chat_id\": \"" + chatId + "\", \"message_id\": " + messageID + "}";
            try (OutputStream os = deleteConnection.getOutputStream()) {
                os.write(deleteJson.getBytes(StandardCharsets.UTF_8));
            }
            deleteConnection.getResponseCode();
        } catch (IOException e) {
            // Не критично — просто логируем
            System.err.println("Ошибка удаления сообщения: " + e.getMessage());
        }
    }

    public static int sendTelegramMessage(String message) {
        if (!isConfigured() || chatId == null) return 0;
        try {
            String urlString = "https://api.telegram.org/bot" + getToken() + "/sendMessage";
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            String jsonInputString = "{\"chat_id\": \"" + chatId + "\", \"text\": \"" + escapeJson(message) + "\"}";
            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonInputString.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream responseStream = connection.getInputStream();
                String response = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
                JSONObject jsonResponse = new JSONObject(response);
                return jsonResponse.getJSONObject("result").getInt("message_id");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
