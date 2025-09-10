package com.example.languageteacherbot.service;

import com.example.languageteacherbot.entity.User;
import com.example.languageteacherbot.entity.Word;
import com.example.languageteacherbot.entity.UserWord;
import com.example.languageteacherbot.repository.UserRepository;
import com.example.languageteacherbot.repository.WordRepository;
import com.example.languageteacherbot.repository.UserWordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TelegramService {

    @Value("${telegram.bot.token}")
    private String BOT_TOKEN;

    private final String SEND_MESSAGE_URL = "https://api.telegram.org/bot";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WordRepository wordRepository;

    @Autowired
    private UserWordRepository userWordRepository;

    // Храним состояние разговора для каждого пользователя
    private final Map<Long, ConversationState> userStates = new HashMap<>();
    // Храним активные сессии игры "Карточки"
    private final Map<Long, FlashcardGameSession> activeFlashcardGames = new HashMap<>();
    // Храним активные сессии игры "Составить предложение"
    private final Map<Long, SentenceGameSession> activeSentenceGames = new HashMap<>();
    // Храним список слов для удаления в "Мои слова" (для сопоставления кнопки с ID слова)
    private final Map<Long, Map<String, Long>> userWordDeleteMap = new HashMap<>(); // chatId -> {buttonText -> wordId}

    // --- Методы отправки сообщений ---

    public void sendMessage(Long chatId, String text) {
        sendMessageWithButtons(chatId, text, null);
    }

    // Исправлено: правильные типы для клавиатуры
    private void sendMessageWithButtons(Long chatId, String text, List<List<String>> buttons) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("text", text);
            request.put("parse_mode", "Markdown");

            if (buttons != null && !buttons.isEmpty()) {
                List<Map<String, Object>> keyboard = new ArrayList<>();
                for (List<String> row : buttons) {
                    List<Map<String, Object>> keyboardRow = new ArrayList<>(); // Используем Object
                    for (String buttonText : row) {
                        Map<String, Object> button = new HashMap<>(); // Используем Object
                        button.put("text", buttonText);
                        keyboardRow.add(button);
                    }
                    keyboard.add(keyboardRow);
                }
                Map<String, Object> replyMarkup = new HashMap<>(); // Используем Object
                replyMarkup.put("keyboard", keyboard);
                replyMarkup.put("resize_keyboard", true);
                replyMarkup.put("one_time_keyboard", false);
                request.put("reply_markup", replyMarkup);
            }

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.postForObject(SEND_MESSAGE_URL + BOT_TOKEN + "/sendMessage", request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Обработка обновлений от Telegram ---

    @SuppressWarnings("unchecked") // Для приведения типов из Map<String, Object>
    public void processUpdate(Map<String, Object> update) {
        try {
            Map<String, Object> message = (Map<String, Object>) update.get("message");
            if (message == null) return;

            // Исправлено: правильное извлечение chatId
            Map<String, Object> chatMap = (Map<String, Object>) message.get("chat");
            Long chatId = ((Number) chatMap.get("id")).longValue(); // Преобразование Number в long
            String text = (String) message.get("text");

            Map<String, Object> fromMap = (Map<String, Object>) message.get("from");
            String firstName = (String) fromMap.get("first_name");
            String lastName = (String) fromMap.get("last_name");

            // Проверяем, активна ли какая-либо игра
            if (activeFlashcardGames.containsKey(chatId)) {
                handleFlashcardGameInput(chatId, text);
                return;
            }
            if (activeSentenceGames.containsKey(chatId)) {
                handleSentenceGameInput(chatId, text);
                return;
            }

            // Обрабатываем в зависимости от текущего состояния пользователя
            ConversationState state = userStates.getOrDefault(chatId, ConversationState.START);
            switch (state) {
                case START -> handleStart(chatId, firstName, lastName);
                case AWAITING_NATIVE_LANG -> handleNativeLanguageSelection(chatId, text);
                case AWAITING_TARGET_LANG -> handleTargetLanguageSelection(chatId, text);
                case AWAITING_LEVEL -> handleLevelSelection(chatId, text);
                case IN_MENU -> handleMenuCommand(chatId, text);
                case IN_MY_WORDS -> handleMyWordsCommand(chatId, text); // Новый обработчик для "Мои слова"
                case IN_SENTENCE_GAME -> handleSentenceGameInput(chatId, text); // На случай, если состояние не сбросилось
                default -> {
                    sendMessage(chatId, "Произошла ошибка. Пожалуйста, начните сначала с команды /start.");
                    userStates.put(chatId, ConversationState.START);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Логика команд и состояний ---

    private void handleStart(Long chatId, String firstName, String lastName) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        User user;
        if (userOpt.isPresent()) {
            user = userOpt.get();
            user.setLastActivityAt(LocalDateTime.now());
            userRepository.save(user);
            sendMessage(chatId, "С возвращением, " + firstName + "! 👋");
            showMainMenu(chatId);
        } else {
            user = new User();
            user.setChatId(chatId);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setRegisteredAt(LocalDateTime.now());
            user.setLastActivityAt(LocalDateTime.now());
            userRepository.save(user);

            String welcomeText = "Привет, " + firstName + "! 👋\n" +
                    "Я твой помощник в изучении русского и китайского языков!\n" +
                    "Для начала выбери свой родной язык:";
            List<List<String>> languageButtons = List.of(
                    List.of("🇷🇺 Русский", "🇨🇳 中文")
            );
            sendMessageWithButtons(chatId, welcomeText, languageButtons);
            userStates.put(chatId, ConversationState.AWAITING_NATIVE_LANG);
        }
    }

    private void handleNativeLanguageSelection(Long chatId, String selectedLanguage) {
        String nativeLangCode;
        if (selectedLanguage.equals("🇷🇺 Русский")) {
            nativeLangCode = "ru";
        } else if (selectedLanguage.equals("🇨🇳 中文")) {
            nativeLangCode = "zh";
        } else {
            sendMessage(chatId, "Пожалуйста, выбери язык из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setNativeLanguage(nativeLangCode);
            userRepository.save(user);

            String targetLangText = "Отлично! Теперь выбери язык, который ты хочешь изучать:";
            List<List<String>> targetLangButtons;
            if ("ru".equals(nativeLangCode)) {
                targetLangButtons = List.of(List.of("🇨🇳 中文"));
            } else { // "zh"
                targetLangButtons = List.of(List.of("🇷🇺 Русский"));
            }
            sendMessageWithButtons(chatId, targetLangText, targetLangButtons);
            userStates.put(chatId, ConversationState.AWAITING_TARGET_LANG);
        } else {
            sendMessage(chatId, "Ошибка. Пожалуйста, начни сначала с /start.");
            userStates.put(chatId, ConversationState.START);
        }
    }

    private void handleTargetLanguageSelection(Long chatId, String selectedLanguage) {
        String targetLangCode;
        if (selectedLanguage.equals("🇷🇺 Русский")) {
            targetLangCode = "ru";
        } else if (selectedLanguage.equals("🇨🇳 中文")) {
            targetLangCode = "zh";
        } else {
            sendMessage(chatId, "Пожалуйста, выбери язык из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setTargetLanguage(targetLangCode);
            userRepository.save(user);

            String levelText = "Выбери свой уровень знаний:";
            List<List<String>> levelButtons = List.of(
                    List.of("A1", "A2"),
                    List.of("B1", "B2"),
                    List.of("C1", "C2")
            );
            sendMessageWithButtons(chatId, levelText, levelButtons);
            userStates.put(chatId, ConversationState.AWAITING_LEVEL);
        } else {
            sendMessage(chatId, "Ошибка. Пожалуйста, начни сначала с /start.");
            userStates.put(chatId, ConversationState.START);
        }
    }

    private void handleLevelSelection(Long chatId, String selectedLevel) {
        if (!List.of("A1", "A2", "B1", "B2", "C1", "C2").contains(selectedLevel)) {
            sendMessage(chatId, "Пожалуйста, выбери уровень из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setLevel(selectedLevel);
            userRepository.save(user);

            String confirmationText = "Отлично! Ты выбрал уровень *" + selectedLevel + "* для изучения языка *" +
                    ("ru".equals(user.getTargetLanguage()) ? "Русский" : "Китайский") + "*.";
            sendMessage(chatId, confirmationText);
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
        } else {
            sendMessage(chatId, "Ошибка. Пожалуйста, начни сначала с /start.");
            userStates.put(chatId, ConversationState.START);
        }
    }

    private void showMainMenu(Long chatId) {
        String menuText = "🎯 *Главное меню*";
        List<List<String>> menuButtons = List.of(
                List.of("🎮 Игры"),
                List.of("📘 Словарь", "🔁 Мои слова"),
                List.of("⚙️ Настройки")
        );
        sendMessageWithButtons(chatId, menuText, menuButtons);
        userStates.put(chatId, ConversationState.IN_MENU); // Убедимся, что состояние установлено
    }

    private void handleMenuCommand(Long chatId, String command) {
        switch (command) {
            case "🎮 Игры" -> showGamesMenu(chatId);
            case "📘 Словарь" -> showDictionary(chatId);
            case "🔁 Мои слова" -> showMyWords(chatId); // Это установит состояние IN_MY_WORDS
            case "⚙️ Настройки" -> showSettings(chatId);
            case "/start" -> {
                Optional<User> userOpt = userRepository.findByChatId(chatId);
                if(userOpt.isPresent()) {
                    showMainMenu(chatId);
                    // userStates уже установлено в showMainMenu
                } else {
                    handleStart(chatId, "User", ""); // Передаем заглушки, так как имя неизвестно
                }
            }
            default -> {
                sendMessage(chatId, "Неизвестная команда. Пожалуйста, используй меню.");
                showMainMenu(chatId);
            }
        }
    }

    // --- Игры ---

    private void showGamesMenu(Long chatId) {
        String gamesText = "🎲 *Выбери игру:*";
        List<List<String>> gameButtons = List.of(
                List.of("낱말 카드 (Карточки)", "문장 만들기 (Составить предложение)")
        );
        sendMessageWithButtons(chatId, gamesText, gameButtons);
        // Состояние не меняем, остаемся в меню до выбора игры
    }

    // --- Игра "Карточки" (Flashcards) ---

    private void startFlashcardGame(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        User user = userOpt.get();
        List<Word> words = wordRepository.findByLevelAndLang(user.getLevel(), user.getTargetLanguage());

        if (words.isEmpty()) {
            sendMessage(chatId, "😔 Нет слов для этого уровня. Попробуй другой уровень или язык.");
            showMainMenu(chatId);
            return;
        }

        Collections.shuffle(words); // Перемешиваем слова

        FlashcardGameSession session = new FlashcardGameSession(chatId, "flashcard", words, 0);
        activeFlashcardGames.put(chatId, session);

        sendFlashcard(chatId, session);
        // Состояние не меняем, обработка идет через activeFlashcardGames
    }

    private void sendFlashcard(Long chatId, FlashcardGameSession session) {
        int index = session.getCurrentIndex();
        List<Word> words = session.getWords();

        if (index >= words.size()) {
            finishFlashcardGame(chatId, session);
            return;
        }

        Word currentWord = words.get(index);
        String question = "🔤 *Переведи слово:*\n\n" + currentWord.getWord();
        String instruction = "\n\n(Напиши перевод или нажми 'Не знаю')";

        List<List<String>> buttons = List.of(List.of("Не знаю"));
        sendMessageWithButtons(chatId, question + instruction, buttons);
    }

    private void handleFlashcardGameInput(Long chatId, String userAnswer) {
        FlashcardGameSession session = activeFlashcardGames.get(chatId);
        if (session == null) {
            sendMessage(chatId, "Игра не найдена. Вернись в меню.");
            showMainMenu(chatId);
            return;
        }

        List<Word> words = session.getWords();
        int index = session.getCurrentIndex();
        Word currentWord = words.get(index);

        String correctAnswer = currentWord.getTranslation();
        String response;

        if (userAnswer.equals("Не знаю")) {
            response = "🔹 Правильный перевод: *" + correctAnswer + "*";
            // Добавляем слово в "Мои слова"
            addToMyWords(chatId, currentWord);
        } else {
            if (userAnswer.trim().equalsIgnoreCase(correctAnswer)) {
                response = "✅ Правильно!";
            } else {
                response = "❌ Неправильно.\nПравильный перевод: *" + correctAnswer + "*";
                // Добавляем слово в "Мои слова"
                addToMyWords(chatId, currentWord);
            }
        }

        sendMessage(chatId, response);

        session.setCurrentIndex(index + 1);
        activeFlashcardGames.put(chatId, session); // Обновляем сессию

        if (session.getCurrentIndex() >= words.size()) {
            finishFlashcardGame(chatId, session);
        } else {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            sendFlashcard(chatId, session);
        }
    }

    private void finishFlashcardGame(Long chatId, FlashcardGameSession session) {
        activeFlashcardGames.remove(chatId);
        sendMessage(chatId, "🎉 Игра 'Карточки' окончена! Хорошая работа!");
        showMainMenu(chatId);
    }

    // --- Игра "Составить предложение" (Sentence) ---

    private void startSentenceGame(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        User user = userOpt.get();
        List<Word> words = wordRepository.findByLevelAndLang(user.getLevel(), user.getTargetLanguage());

        if (words.size() < 3) { // Минимум 3 слова для игры
            sendMessage(chatId, "😔 Недостаточно слов для этой игры на твоём уровне. Попробуй другой уровень или язык.");
            showMainMenu(chatId);
            return;
        }

        // Перемешиваем и берём 3-5 случайных слов
        Collections.shuffle(words);
        List<Word> selectedWords = words.subList(0, Math.min(5, words.size()));

        // Создаем простое предложение (заглушка)
        String correctSentence = createSimpleSentence(selectedWords, user.getTargetLanguage());

        // Создаем сессию игры
        SentenceGameSession session = new SentenceGameSession(chatId, selectedWords, correctSentence);
        activeSentenceGames.put(chatId, session);

        // Формируем сообщение с инструкцией и словами
        StringBuilder sb = new StringBuilder();
        sb.append("✍️ *Составь предложение из этих слов:*\n\n");
        List<String> wordList = selectedWords.stream().map(Word::getWord).collect(Collectors.toList());
        sb.append(String.join(", ", wordList));
        sb.append("\n\nНапиши предложение в чат.");

        sendMessage(chatId, sb.toString());
        userStates.put(chatId, ConversationState.IN_SENTENCE_GAME); // Устанавливаем состояние
    }

    // Очень простая заглушка для генерации предложения
    private String createSimpleSentence(List<Word> words, String lang) {
        if ("ru".equalsIgnoreCase(lang) && words.size() >= 3) {
            // Пример: [я, есть, яблоко] -> "Я ем яблоко."
            return words.get(0).getWord() + " " + words.get(1).getWord() + " " + words.get(2).getWord() + ".";
        } else if ("zh".equalsIgnoreCase(lang) && words.size() >= 3) {
            // Пример: [我, 吃, 苹果] -> "我吃苹果。"
            return words.get(0).getWord() + words.get(1).getWord() + words.get(2).getWord() + "。";
        }
        // Для простоты просто соединяем слова
        return words.stream().map(Word::getWord).collect(Collectors.joining(" ")) + ".";
    }

    private void handleSentenceGameInput(Long chatId, String userSentence) {
        SentenceGameSession session = activeSentenceGames.get(chatId);
        if (session == null) {
             // Если игра не активна, возможно пользователь просто написал что-то в меню
             sendMessage(chatId, "Неизвестная команда. Пожалуйста, используй меню.");
             showMainMenu(chatId);
             userStates.put(chatId, ConversationState.IN_MENU); // Сбрасываем состояние
            return;
        }

        String correctSentence = session.getCorrectSentence();
        String response;

        // Простая проверка: игнорируем регистр и пробелы в конце
        if (userSentence.trim().equalsIgnoreCase(correctSentence.trim())) {
            response = "✅ Правильно! Отличное предложение!";
        } else {
            response = "❌ Неправильно.\nПравильный вариант: *" + correctSentence + "*";
        }

        sendMessage(chatId, response);
        activeSentenceGames.remove(chatId); // Завершаем сессию игры
        userStates.put(chatId, ConversationState.IN_MENU); // Возвращаемся в меню
        showMainMenu(chatId);
    }


    // --- Словари и списки ---

    private void showDictionary(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }
        User user = userOpt.get();

        List<Word> words = wordRepository.findByLevelAndLang(user.getLevel(), user.getTargetLanguage());
        if (words.isEmpty()) {
            sendMessage(chatId, "😔 Нет слов для этого уровня.");
            showMainMenu(chatId);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📘 *Словарь (Уровень ").append(user.getLevel()).append(")*\n\n");
        for (int i = 0; i < Math.min(words.size(), 30); i++) { // Ограничиваем 30 словами
            Word w = words.get(i);
            sb.append(w.getWord()).append(" - ").append(w.getTranslation()).append("\n");
        }
        if (words.size() > 30) {
            sb.append("\n... и ещё ").append(words.size() - 30).append(" слов.");
        }

        sendMessage(chatId, sb.toString());
        showMainMenu(chatId);
    }

    private void showMyWords(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        List<UserWord> userWords = userWordRepository.findByUserChatId(chatId);
        if (userWords.isEmpty()) {
            sendMessage(chatId, "🔁 Ты ещё не отметил ни одного слова как 'не знаю'.");
            showMainMenu(chatId);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔁 *Твои слова (Не знаю)*\n\n");

        List<List<String>> buttons = new ArrayList<>();
        List<String> row = new ArrayList<>();
        Map<String, Long> deleteMap = new HashMap<>(); // Локальная карта для этого отображения

        for (int i = 0; i < Math.min(userWords.size(), 20); i++) { // Ограничиваем 20 словами для кнопок
            UserWord uw = userWords.get(i);
            sb.append((i+1)).append(". ").append(uw.getWord().getWord()).append(" - ").append(uw.getWord().getTranslation()).append("\n");

            // Создаем кнопку для удаления
            String buttonText = "❌ " + uw.getWord().getWord();
            deleteMap.put(buttonText, uw.getWord().getId()); // Сопоставляем текст кнопки с ID слова
            row.add(buttonText);

            // Добавляем новую строку каждые 2 кнопки
            if (row.size() == 2) {
                buttons.add(new ArrayList<>(row));
                row.clear();
            }
        }

        // Добавляем последнюю неполную строку, если она есть
        if (!row.isEmpty()) {
            buttons.add(row);
        }

        // Добавляем кнопку "Назад в меню"
        buttons.add(List.of("⬅️ Назад в меню"));

        // Сохраняем карту удаления для этого пользователя
        userWordDeleteMap.put(chatId, deleteMap);

        sendMessageWithButtons(chatId, sb.toString(), buttons);
        userStates.put(chatId, ConversationState.IN_MY_WORDS); // Устанавливаем состояние
    }

    // Новый обработчик команд, когда пользователь находится в списке "Мои слова"
    private void handleMyWordsCommand(Long chatId, String command) {
        if (command.equals("⬅️ Назад в меню")) {
            userStates.put(chatId, ConversationState.IN_MENU);
            showMainMenu(chatId);
            userWordDeleteMap.remove(chatId); // Очищаем временную карту
            return;
        } else if (command.startsWith("❌ ")) {
            // Это команда удаления
            handleDeleteWord(chatId, command);
            return; // Не показываем меню снова, так как showMyWords это сделает
        } else {
             // Пользователь ввел что-то другое, возможно, думал, что это меню
             sendMessage(chatId, "Для взаимодействия с 'Моими словами' используй кнопки.");
             // Повторно показываем список
             showMyWords(chatId); // Это снова установит состояние IN_MY_WORDS
        }
    }

    private void handleDeleteWord(Long chatId, String buttonCommand) {
        Map<String, Long> deleteMap = userWordDeleteMap.get(chatId);
        if (deleteMap == null || !deleteMap.containsKey(buttonCommand)) {
            sendMessage(chatId, "❌ Ошибка при удалении слова.");
            showMyWords(chatId); // Перезагружаем список
            return;
        }

        Long wordIdToDelete = deleteMap.get(buttonCommand);
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            userWordDeleteMap.remove(chatId);
            return;
        }

        Optional<UserWord> userWordOpt = userWordRepository.findByUserChatIdAndWordId(chatId, wordIdToDelete);
        if (userWordOpt.isPresent()) {
            userWordRepository.delete(userWordOpt.get());
            // Получаем слово для сообщения
            Optional<Word> wordOpt = wordRepository.findById(wordIdToDelete);
            String wordStr = wordOpt.map(Word::getWord).orElse("слово");
            sendMessage(chatId, "✅ Слово *" + wordStr + "* удалено из твоего списка.");
        } else {
            sendMessage(chatId, "❌ Слово не найдено в твоем списке.");
        }

        // После удаления снова показываем обновленный список
        showMyWords(chatId); // Это обновит deleteMap и состояние
    }


    private void showSettings(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String settingsText = "⚙️ *Твои настройки:*\n" +
                    "Родной язык: " + ("ru".equals(user.getNativeLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                    "Изучаемый язык: " + ("ru".equals(user.getTargetLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                    "Уровень: " + user.getLevel() + "\n\n" +
                    "Чтобы изменить, начни сначала с команды /start.";
            sendMessage(chatId, settingsText);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
        }
        showMainMenu(chatId);
    }

    // --- Вспомогательные методы ---

    private void addToMyWords(Long chatId, Word word) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            Optional<UserWord> existingUW = userWordRepository.findByUserChatIdAndWordId(chatId, word.getId());
            if (existingUW.isEmpty()) {
                UserWord uw = new UserWord();
                uw.setUserChatId(chatId);
                uw.setWord(word);
                userWordRepository.save(uw);
            }
        }
    }

    // --- Вспомогательные классы для сессий игр ---

    private enum ConversationState {
        START, AWAITING_NATIVE_LANG, AWAITING_TARGET_LANG, AWAITING_LEVEL, IN_MENU, IN_MY_WORDS, IN_SENTENCE_GAME
    }

    private static class FlashcardGameSession {
        private final Long userId;
        private final String gameType;
        private final List<Word> words;
        private int currentIndex;

        public FlashcardGameSession(Long userId, String gameType, List<Word> words, int currentIndex) {
            this.userId = userId;
            this.gameType = gameType; // Всегда "flashcard" для этой сессии
            this.words = new ArrayList<>(words); // Копируем список
            this.currentIndex = currentIndex;
        }

        // Геттеры и сеттеры
        public Long getUserId() { return userId; }
        public String getGameType() { return gameType; }
        public List<Word> getWords() { return words; }
        public int getCurrentIndex() { return currentIndex; }
        public void setCurrentIndex(int currentIndex) { this.currentIndex = currentIndex; }
    }

    private static class SentenceGameSession {
        private final Long userId;
        private final List<Word> words;
        private final String correctSentence;

        public SentenceGameSession(Long userId, List<Word> words, String correctSentence) {
            this.userId = userId;
            this.words = new ArrayList<>(words); // Копируем список
            this.correctSentence = correctSentence;
        }

        // Геттеры
        public Long getUserId() { return userId; }
        public List<Word> getWords() { return words; }
        public String getCorrectSentence() { return correctSentence; }
    }
}