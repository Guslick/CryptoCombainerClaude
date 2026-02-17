package ton.dariushkmetsyak.Telegram;

import org.json.JSONObject;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;



public class ImageAndMessageSender {

    // Токен вашего бота
    private static final String BOT_TOKEN = "7420980540:AAENPop_SY3bBVHl8kNxT97Mxazxthvk8Jo";
     private static String chatId ;
//             = "-1002382149738";
//    final private static String chatId = "-1002453915115";
//   final private static String chatId = "-1002382149738";


    // Метод отправки изображения

    public static void setChatId(long chatId) {
        ImageAndMessageSender.chatId = String.valueOf(chatId);
    }
    public static void sendPhoto(String filePath, String caption, long chatId) {
    setChatId(chatId);
    sendPhoto(filePath, caption);
    }
    public static int sendPhoto(String filePath, String caption) {
        try {
            // URL для метода sendPhoto
            String apiUrl = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendPhoto";

            // Создание подключения
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=boundary");

            // Формирование данных для отправки
            OutputStream outputStream = conn.getOutputStream();

            // Добавляем chat_id
            outputStream.write(("--boundary\r\n").getBytes());
            outputStream.write(("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n").getBytes());
            outputStream.write((chatId + "\r\n").getBytes());

            // Добавляем подпись (caption)
            if (caption != null) {
                outputStream.write(("--boundary\r\n").getBytes());
                outputStream.write(("Content-Disposition: form-data; name=\"caption\"\r\n\r\n").getBytes());
                outputStream.write((caption + "\r\n").getBytes());
            }

            // Добавляем файл (photo)
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

            // Закрываем boundary
            outputStream.write(("\r\n--boundary--\r\n").getBytes());
            outputStream.flush();
            outputStream.close();

            // Чтение ответа от Telegram
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("Изображение отправлено успешно.");
                InputStream responseStream = conn.getInputStream();
                String response = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
                JSONObject jsonResponse = new JSONObject(response);
                return jsonResponse.getJSONObject("result").getInt("message_id");
            } else {
                System.out.println("Ошибка при отправке изображения: " + responseCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static void sendTelegramMessage(String message, long chatId){
        setChatId(chatId);
        sendTelegramMessage(message);
    }

    public  static void deleteMessage(int messageID){

        HttpURLConnection deleteConnection = null;
        try {     String deleteUrlString = "https://api.telegram.org/bot" + BOT_TOKEN + "/deleteMessage";
            URL deleteUrl = new URL(deleteUrlString);
            deleteConnection = (HttpURLConnection) deleteUrl.openConnection();
            deleteConnection.setRequestMethod("POST");
            deleteConnection.setRequestProperty("Content-Type", "application/json; utf-8");
            deleteConnection.setDoOutput(true);
            String deleteJson = "{\"chat_id\": \"" + chatId + "\", \"message_id\": " + messageID + "}";
            try (OutputStream os = deleteConnection.getOutputStream()) {
                byte[] input = deleteJson.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            int deleteResponseCode = deleteConnection.getResponseCode();
            if (deleteResponseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("Сообщение удалено.");
            } else {
                //  System.out.println("Ошибка при удалении сообщения. Код: " + deleteResponseCode);
            }
        } catch (IOException e) {
            sendTelegramMessage("Ошибка при удалении предыдущего сообщения");
            sendTelegramMessage(e.getMessage());
        }



// Указываем chat_id и message_id



    }


    public static int sendTelegramMessage(String message) {
        try {
            // Формируем URL для отправки сообщения
            String urlString = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Настраиваем запрос
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            // Формируем тело запроса в формате JSON
            String jsonInputString = "{\"chat_id\": \"" + chatId + "\", \"text\": \"" + message + "\"}";

            // Отправляем тело запроса
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Получаем ответ от сервера
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("Сообщение успешно отправлено!");
                InputStream responseStream = connection.getInputStream();
                String response = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
                JSONObject jsonResponse = new JSONObject(response);
                return jsonResponse.getJSONObject("result").getInt("message_id");
            } else {
                System.out.println("Ошибка при отправке сообщения. Код ответа: " + responseCode);

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }
}
