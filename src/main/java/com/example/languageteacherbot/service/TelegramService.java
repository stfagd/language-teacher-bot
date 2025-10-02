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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Map<Long, ConversationState> userStates = new HashMap<>();
    private final Map<Long, FlashcardGameSession> activeFlashcardGames = new HashMap<>();
    private final Map<Long, SentenceGameSession> activeSentenceGames = new HashMap<>();
    private final Map<Long, Map<String, Long>> userWordDeleteMap = new HashMap<>();
    private final Map<Long, Integer> userDictionaryPage = new ConcurrentHashMap<>();

    public void sendMessage(Long chatId, String text) {
        sendMessageWithButtons(chatId, text, null);
    }

    private void sendMessageWithButtons(Long chatId, String text, List<List<String>> buttons) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("text", text);
            request.put("parse_mode", "Markdown");

            if (buttons != null && !buttons.isEmpty()) {
                List<List<Map<String, Object>>> keyboard = new ArrayList<>();
                for (List<String> row : buttons) {
                    List<Map<String, Object>> keyboardRow = new ArrayList<>();
                    for (String buttonText : row) {
                        Map<String, Object> button = new HashMap<>();
                        button.put("text", buttonText);
                        keyboardRow.add(button);
                    }
                    keyboard.add(keyboardRow);
                }

                Map<String, Object> replyMarkup = new HashMap<>();
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

    @SuppressWarnings("unchecked")
    public void processUpdate(Map<String, Object> update) {
        try {
            if (update.containsKey("callback_query")) {
                Map<String, Object> callbackQuery = (Map<String, Object>) update.get("callback_query");
                String data = (String) callbackQuery.get("data");
                Map<String, Object> message = (Map<String, Object>) callbackQuery.get("message");
                Long chatId = ((Number) ((Map<String, Object>) message.get("chat")).get("id")).longValue();
                Integer messageId = ((Number) message.get("message_id")).intValue();

                if (data.startsWith("dict_prev:")) {
                    int page = Integer.parseInt(data.split(":")[1]);
                    userDictionaryPage.put(chatId, page);
                    editMessageWithDictionary(chatId, messageId);
                } else if (data.startsWith("dict_next:")) {
                    int page = Integer.parseInt(data.split(":")[1]);
                    userDictionaryPage.put(chatId, page);
                    editMessageWithDictionary(chatId, messageId);
                } else if (data.equals("main_menu")) {
                    showMainMenu(chatId);
                }
                return;
            }

            Map<String, Object> message = (Map<String, Object>) update.get("message");
            if (message == null) return;

            Map<String, Object> chatMap = (Map<String, Object>) message.get("chat");
            Long chatId = ((Number) chatMap.get("id")).longValue();
            String text = (String) message.get("text");

            Map<String, Object> fromMap = (Map<String, Object>) message.get("from");
            String firstName = (String) fromMap.get("first_name");
            String lastName = (String) fromMap.get("last_name");

            if (activeFlashcardGames.containsKey(chatId)) {
                handleFlashcardGameInput(chatId, text);
                return;
            }
            if (activeSentenceGames.containsKey(chatId)) {
                handleSentenceGameInput(chatId, text);
                return;
            }

            ConversationState state = userStates.getOrDefault(chatId, ConversationState.START);
            switch (state) {
                case START -> handleStart(chatId, firstName, lastName);
                case AWAITING_NATIVE_LANG -> handleNativeLanguageSelection(chatId, text);
                case AWAITING_TARGET_LANG -> handleTargetLanguageSelection(chatId, text);
                case AWAITING_LEVEL -> handleLevelSelection(chatId, text);
                case IN_MENU -> handleMenuCommand(chatId, text);
                case IN_MY_WORDS -> handleMyWordsCommand(chatId, text);
                case IN_SENTENCE_GAME -> handleSentenceGameInput(chatId, text);
                case IN_SETTINGS -> handleSettingsCommand(chatId, text);
                case IN_DICTIONARY -> handleDictionaryCommand(chatId, text);
                case AWAITING_NEW_NATIVE_LANG -> handleNewNativeLanguageSelection(chatId, text);
                case AWAITING_NEW_TARGET_LANG -> handleNewTargetLanguageSelection(chatId, text);
                case AWAITING_NEW_LEVEL -> handleNewLevelSelection(chatId, text);
                default -> {
                    sendMessage(chatId, "Произошла ошибка. Пожалуйста, начните сначала с команды /start.");
                    userStates.put(chatId, ConversationState.START);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleStart(Long chatId, String firstName, String lastName) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        User user;
        if (userOpt.isPresent()) {
            user = userOpt.get();
            user.setLastActivityAt(LocalDateTime.now());
            userRepository.save(user);
            String nativeLang = user.getNativeLanguage();
            String welcomeBackText = nativeLang.equals("ru") ? "С возвращением, " : "欢迎回来，";
            sendMessage(chatId, welcomeBackText + firstName + "! 👋");
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
        } else {
            user = new User();
            user.setChatId(chatId);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setRegisteredAt(LocalDateTime.now());
            user.setLastActivityAt(LocalDateTime.now());
            userRepository.save(user);

            String welcomeText = "你好，" + firstName + "! 👋\n" +
                    "我是你学习俄语和汉语的助手!\n" +
                    "首先，选择您的母语。:\n" +
                    "Привет, " + firstName + "! 👋\n" +
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
        String targetLangText;
        List<List<String>> targetLangButtons;

        if (selectedLanguage.equals("🇷🇺 Русский")) {
            nativeLangCode = "ru";
            targetLangText = "Отлично! Теперь выбери язык, который ты хочешь изучать:";
            targetLangButtons = List.of(List.of("🇨🇳 中文"));
        } else if (selectedLanguage.equals("🇨🇳 中文")) {
            nativeLangCode = "zh";
            targetLangText = "很好！现在选择你想学习的语言：";
            targetLangButtons = List.of(List.of("🇷🇺 Русский"));
        } else {
            sendMessage(chatId, "Пожалуйста, выбери язык из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setNativeLanguage(nativeLangCode);
            userRepository.save(user);

            sendMessageWithButtons(chatId, targetLangText, targetLangButtons);
            userStates.put(chatId, ConversationState.AWAITING_TARGET_LANG);
        } else {
            sendMessage(chatId, nativeLangCode.equals("ru") ? "Ошибка. Пожалуйста, начни сначала с /start." : "错误。请从 /start 重新开始。");
            userStates.put(chatId, ConversationState.START);
        }
    }

    private void handleTargetLanguageSelection(Long chatId, String selectedLanguage) {
        String targetLangCode;
        String levelText;
        List<List<String>> levelButtons;

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка. Пожалуйста, начни сначала с /start.");
            userStates.put(chatId, ConversationState.START);
            return;
        }
        User user = userOpt.get();
        String nativeLang = user.getNativeLanguage();

        if (nativeLang.equals("ru") && selectedLanguage.equals("🇨🇳 中文")) {
            targetLangCode = "zh";
        } else if (nativeLang.equals("zh") && selectedLanguage.equals("🇷🇺 Русский")) {
            targetLangCode = "ru";
        } else {
            String errorMessage = nativeLang.equals("ru") ? "Пожалуйста, выбери язык из предложенных вариантов." : "请选择提供的选项之一。";
            sendMessage(chatId, errorMessage);
            String targetLangText = nativeLang.equals("ru") ? "Отлично! Теперь выбери язык, который ты хочешь изучать:" : "很好！现在选择你想学习的语言：";
            List<List<String>> targetLangButtons = nativeLang.equals("ru") ? List.of(List.of("🇨🇳 中文")) : List.of(List.of("🇷🇺 Русский"));
            sendMessageWithButtons(chatId, targetLangText, targetLangButtons);
            return;
        }

        user.setTargetLanguage(targetLangCode);
        userRepository.save(user);

        if (nativeLang.equals("ru")) {
            levelText = "Выбери свой уровень знаний:";
            levelButtons = List.of(
                    List.of("A1", "A2"),
                    List.of("B1", "B2"),
                    List.of("C1", "C2")
            );
        } else {
            levelText = "选择你的知识水平：";
            levelButtons = List.of(
                    List.of("A1", "A2"),
                    List.of("B1", "B2"),
                    List.of("C1", "C2")
            );
        }

        sendMessageWithButtons(chatId, levelText, levelButtons);
        userStates.put(chatId, ConversationState.AWAITING_LEVEL);
    }

    private void handleLevelSelection(Long chatId, String selectedLevel) {
        if (!List.of("A1", "A2", "B1", "B2", "C1", "C2").contains(selectedLevel)) {
            Optional<User> userOpt = userRepository.findByChatId(chatId);
            String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
            String errorMessage = nativeLang.equals("ru") ? "Пожалуйста, выбери уровень из предложенных вариантов." : "请选择提供的级别之一。";
            sendMessage(chatId, errorMessage);
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setLevel(selectedLevel);
            userRepository.save(user);

            String nativeLang = user.getNativeLanguage();
            String targetLangName = ("ru".equals(user.getTargetLanguage()) ? (nativeLang.equals("ru") ? "Русский" : "俄语") : (nativeLang.equals("ru") ? "Китайский" : "中文"));
            String confirmationText;
            if (nativeLang.equals("ru")) {
                confirmationText = "Отлично! Ты выбрал уровень *" + selectedLevel + "* для изучения языка *" + targetLangName + "*.";
            } else {
                confirmationText = "很好！你选择了 *" + selectedLevel + "* 级别来学习 *" + targetLangName + "*。";
            }

            sendMessage(chatId, confirmationText);
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
        } else {
            sendMessage(chatId, "Ошибка. Пожалуйста, начни сначала с /start.");
            userStates.put(chatId, ConversationState.START);
        }
    }

    private void showMainMenu(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String menuText;
        List<List<String>> menuButtons;

        if (nativeLang.equals("ru")) {
            menuText = "🎯 *Главное меню*";
            menuButtons = List.of(
                    List.of("🎮 Игры"),
                    List.of("📘 Словарь", "🔁 Мои слова"),
                    List.of("⚙️ Настройки")
            );
        } else {
            menuText = "🎯 *主菜单*";
            menuButtons = List.of(
                    List.of("🎮 游戏"),
                    List.of("📘 词典", "🔁 我的单词"),
                    List.of("⚙️ 设置")
            );
        }

        sendMessageWithButtons(chatId, menuText, menuButtons);
        userStates.put(chatId, ConversationState.IN_MENU);
    }

    private void handleMenuCommand(Long chatId, String command) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String gamesCmd = nativeLang.equals("ru") ? "🎮 Игры" : "🎮 游戏";
        String dictCmd = nativeLang.equals("ru") ? "📘 Словарь" : "📘 词典";
        String myWordsCmd = nativeLang.equals("ru") ? "🔁 Мои слова" : "🔁 我的单词";
        String settingsCmd = nativeLang.equals("ru") ? "⚙️ Настройки" : "⚙️ 设置";

        if (command.equals(gamesCmd)) {
            showGamesMenu(chatId);
        } else if (command.equals(dictCmd)) {
            userDictionaryPage.put(chatId, 0);
            showDictionary(chatId);
        } else if (command.equals(myWordsCmd)) {
            showMyWords(chatId);
        } else if (command.equals(settingsCmd)) {
            showSettings(chatId);
        } else if (command.equals("/start")) {
            if(userOpt.isPresent()) {
                showMainMenu(chatId);
            } else {
                handleStart(chatId, "User", "");
            }
        } else if (command.equals(nativeLang.equals("ru") ? "Flash card (Карточки)" : "Flash card (单词卡片)")) {
            startFlashcardGame(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "Sentence (Составить предложение)" : "Sentence (造句)")) {
            startSentenceGame(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页")) {
            int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
            if (currentPage > 0) {
                userDictionaryPage.put(chatId, currentPage - 1);
            }
            showDictionary(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️")) {
            int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
            int totalPages = (int) Math.ceil((double) wordRepository.findByLevelAndLang(
                userOpt.get().getLevel(), userOpt.get().getTargetLanguage()).size() / 30.0);
            if (currentPage < totalPages - 1) {
                userDictionaryPage.put(chatId, currentPage + 1);
            }
            showDictionary(chatId);
        } else if (command.equals(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单")) {
            showMainMenu(chatId);
        } else {
            String message = nativeLang.equals("ru") ? "Неизвестная команда. Пожалуйста, используй меню." : "未知命令。请使用菜单。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
        }
    }

    private void showGamesMenu(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String gamesText;
        List<List<String>> gameButtons;

        if (nativeLang.equals("ru")) {
            gamesText = "🎲 *Выбери игру:*";
            gameButtons = List.of(
                    List.of("Flash card (Карточки)", "Sentence (Составить предложение)"),
                    List.of("⬅️ Назад в меню")
            );
        } else {
            gamesText = "🎲 *选择游戏:*";
            gameButtons = List.of(
                    List.of("Flash card (单词卡片)", "Sentence (造句)"),
                    List.of("⬅️ 返回菜单")
            );
        }

        sendMessageWithButtons(chatId, gamesText, gameButtons);
    }

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
            String nativeLang = user.getNativeLanguage();
            String message = nativeLang.equals("ru") ? "😔 Нет слов для этого уровня. Попробуй другой уровень или язык." : "😔 此级别没有单词。尝试其他级别或语言。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            return;
        }

        Collections.shuffle(words);

        FlashcardGameSession session = new FlashcardGameSession(chatId, "flashcard", words, 0);
        activeFlashcardGames.put(chatId, session);

        sendFlashcard(chatId, session);
    }

    private void sendFlashcard(Long chatId, FlashcardGameSession session) {
        int index = session.getCurrentIndex();
        List<Word> words = session.getWords();

        if (index >= words.size()) {
            finishFlashcardGame(chatId, session);
            return;
        }

        Word currentWord = words.get(index);

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String question;
        String instruction;
        if (nativeLang.equals("ru")) {
            question = "🔤 *Переведи слово:*\n\n" + currentWord.getWord();
            instruction = "\n\n(Напиши перевод или нажми 'Не знаю')";
        } else {
            question = "🔤 *翻译单词:*\n\n" + currentWord.getWord();
            instruction = "\n\n(写下翻译或点击“不认识”)";
        }

        List<List<String>> buttons = List.of(List.of(nativeLang.equals("ru") ? "Не знаю" : "不认识"));
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

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String response;
        String dontKnowButton = nativeLang.equals("ru") ? "Не знаю" : "不认识";

        if (userAnswer.equals(dontKnowButton)) {
            if (nativeLang.equals("ru")) {
                response = "🔹 Правильный перевод: *" + correctAnswer + "*";
            } else {
                response = "🔹 正确翻译: *" + correctAnswer + "*";
            }
            addToMyWords(chatId, currentWord);
        } else {
            if (userAnswer.trim().equalsIgnoreCase(correctAnswer)) {
                if (nativeLang.equals("ru")) {
                    response = "✅ Правильно!";
                } else {
                    response = "✅ 正确！";
                }
            } else {
                if (nativeLang.equals("ru")) {
                    response = "❌ Неправильно.\nПравильный перевод: *" + correctAnswer + "*";
                } else {
                    response = "❌ 错误。\n正确翻译: *" + correctAnswer + "*";
                }
                addToMyWords(chatId, currentWord);
            }
        }

        sendMessage(chatId, response);

        session.setCurrentIndex(index + 1);
        activeFlashcardGames.put(chatId, session);

        if (session.getCurrentIndex() >= words.size()) {
            finishFlashcardGame(chatId, session);
        } else {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            sendFlashcard(chatId, session);
        }
    }

    private void finishFlashcardGame(Long chatId, FlashcardGameSession session) {
        activeFlashcardGames.remove(chatId);

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String finishMessage;
        if (nativeLang.equals("ru")) {
            finishMessage = "🎉 Игра 'Карточки' окончена! Хорошая работа!";
        } else {
            finishMessage = "🎉 “单词卡片”游戏结束！做得好！";
        }

        sendMessage(chatId, finishMessage);
        showMainMenu(chatId);
    }

    private void startSentenceGame(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }

        User user = userOpt.get();
        List<Word> words = wordRepository.findByLevelAndLang(user.getLevel(), user.getTargetLanguage());

        if (words.size() < 3) {
            String nativeLang = user.getNativeLanguage();
            String message = nativeLang.equals("ru") ? "😔 Недостаточно слов для этой игры на твоём уровне. Попробуй другой уровень или язык." : "😔 你这个级别的游戏单词不够。尝试其他级别或语言。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            return;
        }

        Collections.shuffle(words);
        List<Word> selectedWords = words.subList(0, Math.min(5, words.size()));

        String correctSentence = createSimpleSentence(selectedWords, user.getTargetLanguage());

        SentenceGameSession session = new SentenceGameSession(chatId, selectedWords, correctSentence);
        activeSentenceGames.put(chatId, session);

        StringBuilder sb = new StringBuilder();

        String nativeLang = user.getNativeLanguage();
        if (nativeLang.equals("ru")) {
            sb.append("✍️ *Составь предложение из этих слов:*\n\n");
        } else {
            sb.append("✍️ *用这些词造句:*\n\n");
        }

        List<String> wordList = selectedWords.stream().map(Word::getWord).collect(Collectors.toList());
        sb.append(String.join(", ", wordList));

        if (nativeLang.equals("ru")) {
            sb.append("\n\nНапиши предложение в чат.");
        } else {
            sb.append("\n\n在聊天中写下句子。");
        }

        sendMessage(chatId, sb.toString());
        userStates.put(chatId, ConversationState.IN_SENTENCE_GAME);
    }

    private String createSimpleSentence(List<Word> words, String lang) {
        if ("ru".equalsIgnoreCase(lang) && words.size() >= 3) {
            return words.get(0).getWord() + " " + words.get(1).getWord() + " " + words.get(2).getWord() + ".";
        } else if ("zh".equalsIgnoreCase(lang) && words.size() >= 3) {
            return words.get(0).getWord() + words.get(1).getWord() + words.get(2).getWord() + "。";
        }
        return words.stream().map(Word::getWord).collect(Collectors.joining(" ")) + ".";
    }

    private void handleSentenceGameInput(Long chatId, String userSentence) {
        SentenceGameSession session = activeSentenceGames.get(chatId);
        if (session == null) {
            Optional<User> userOpt = userRepository.findByChatId(chatId);
            String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
            String message = nativeLang.equals("ru") ? "Неизвестная команда. Пожалуйста, используй меню." : "未知命令。请使用菜单。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
            return;
        }

        String correctSentence = session.getCorrectSentence();

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String response;
        if (userSentence.trim().equalsIgnoreCase(correctSentence.trim())) {
            if (nativeLang.equals("ru")) {
                response = "✅ Правильно! Отличное предложение!";
            } else {
                response = "✅ 正确！好句子！";
            }
        } else {
            if (nativeLang.equals("ru")) {
                response = "❌ Неправильно.\nПравильный вариант: *" + correctSentence + "*";
            } else {
                response = "❌ 错误。\n正确答案: *" + correctSentence + "*";
            }
        }

        sendMessage(chatId, response);
        activeSentenceGames.remove(chatId);
        userStates.put(chatId, ConversationState.IN_MENU);
        showMainMenu(chatId);
    }

    private void showDictionary(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }
        User user = userOpt.get();
        String level = user.getLevel();
        String targetLang = user.getTargetLanguage();

        List<Word> allWords = wordRepository.findByLevelAndLang(level, targetLang);

        if (allWords.isEmpty()) {
            String nativeLang = user.getNativeLanguage();
            String message = nativeLang.equals("ru") ? "😔 Нет слов для этого уровня." : "😔 此级别没有单词。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            return;
        }

        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allWords.size() / pageSize);

        if (currentPage >= totalPages) {
            currentPage = Math.max(0, totalPages - 1);
            userDictionaryPage.put(chatId, currentPage);
        }
        if (currentPage < 0) {
            currentPage = 0;
            userDictionaryPage.put(chatId, currentPage);
        }

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allWords.size());
        List<Word> wordsOnPage = allWords.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        String nativeLang = user.getNativeLanguage();

        if (nativeLang.equals("ru")) {
            sb.append("📖 Словарь (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
        } else {
            sb.append("📖 词典 (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
        }

        for (Word w : wordsOnPage) {
            String wordLine;
            if (w.getTranscription() != null && !w.getTranscription().isEmpty()) {
                wordLine = "• " + w.getWord() + " (" + w.getTranscription() + ") — " + w.getTranslation();
            } else {
                wordLine = "• " + w.getWord() + " — " + w.getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createDictionaryInlineKeyboard(chatId, currentPage, totalPages, nativeLang);

        sendMessageWithInlineKeyboard(chatId, sb.toString(), keyboard);
    }

    private void sendMessageWithInlineKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("text", text);
            request.put("parse_mode", "Markdown");
            request.put("reply_markup", keyboard);

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.postForObject(SEND_MESSAGE_URL + BOT_TOKEN + "/sendMessage", request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private InlineKeyboardMarkup createDictionaryInlineKeyboard(Long chatId, int currentPage, int totalPages, String nativeLang) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (currentPage > 0) {
            InlineKeyboardButton backButton = new InlineKeyboardButton();
            backButton.setText(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页");
            backButton.setCallbackData("dict_prev:" + (currentPage - 1));
            navRow.add(backButton);
        }
        if (currentPage < totalPages - 1) {
            InlineKeyboardButton nextButton = new InlineKeyboardButton();
            nextButton.setText(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️");
            nextButton.setCallbackData("dict_next:" + (currentPage + 1));
            navRow.add(nextButton);
        }

        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }

        List<InlineKeyboardButton> menuRow = new ArrayList<>();
        InlineKeyboardButton menuButton = new InlineKeyboardButton();
        menuButton.setText(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单");
        menuButton.setCallbackData("main_menu");
        menuRow.add(menuButton);
        rows.add(menuRow);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private void editMessageWithDictionary(Long chatId, Integer messageId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) return;

        User user = userOpt.get();
        String level = user.getLevel();
        String targetLang = user.getTargetLanguage();

        List<Word> allWords = wordRepository.findByLevelAndLang(level, targetLang);
        int pageSize = 30;
        int totalPages = (int) Math.ceil((double) allWords.size() / pageSize);

        int currentPage = userDictionaryPage.getOrDefault(chatId, 0);

        int fromIndex = currentPage * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allWords.size());
        List<Word> wordsOnPage = allWords.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        String nativeLang = user.getNativeLanguage();

        if (nativeLang.equals("ru")) {
            sb.append("📖 Словарь (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
        } else {
            sb.append("📖 词典 (").append(currentPage + 1).append("/").append(totalPages).append("):\n\n");
        }

        for (Word w : wordsOnPage) {
            String wordLine;
            if (w.getTranscription() != null && !w.getTranscription().isEmpty()) {
                wordLine = "• " + w.getWord() + " (" + w.getTranscription() + ") — " + w.getTranslation();
            } else {
                wordLine = "• " + w.getWord() + " — " + w.getTranslation();
            }
            sb.append(wordLine).append("\n");
        }

        InlineKeyboardMarkup keyboard = createDictionaryInlineKeyboard(chatId, currentPage, totalPages, nativeLang);

        editMessageText(chatId, messageId, sb.toString(), keyboard);
    }

    private void editMessageText(Long chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("message_id", messageId);
            request.put("text", text);
            request.put("parse_mode", "Markdown");
            request.put("reply_markup", keyboard);

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.postForObject(SEND_MESSAGE_URL + BOT_TOKEN + "/editMessageText", request, String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleDictionaryCommand(Long chatId, String text) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            userStates.put(chatId, ConversationState.IN_MENU);
            return;
        }
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        if (text.equals(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页")) {
            int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
            if (currentPage > 0) {
                userDictionaryPage.put(chatId, currentPage - 1);
            }
            showDictionary(chatId);
        } else if (text.equals(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️")) {
            int currentPage = userDictionaryPage.getOrDefault(chatId, 0);
            int totalPages = (int) Math.ceil((double) wordRepository.findByLevelAndLang(
                userOpt.get().getLevel(), userOpt.get().getTargetLanguage()).size() / 30.0);
            if (currentPage < totalPages - 1) {
                userDictionaryPage.put(chatId, currentPage + 1);
            }
            showDictionary(chatId);
        } else if (text.equals(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单")) {
            userStates.put(chatId, ConversationState.IN_MENU);
            showMainMenu(chatId);
        } else {
            showDictionary(chatId);
        }
    }

    private void sendDictionaryPaginationKeyboard(Long chatId, int currentPage, int totalPages) {
        String nativeLang = userRepository.findByChatId(chatId).map(User::getNativeLanguage).orElse("ru");

        List<List<String>> buttons = new ArrayList<>();

        List<String> navRow = new ArrayList<>();
        if (currentPage > 0) {
            navRow.add(nativeLang.equals("ru") ? "⬅️ Назад" : "⬅️ 上一页");
        }
        if (currentPage < totalPages - 1) {
            navRow.add(nativeLang.equals("ru") ? "Вперёд ➡️" : "下一页 ➡️");
        }
        if (!navRow.isEmpty()) {
            buttons.add(navRow);
        }

        buttons.add(List.of(nativeLang.equals("ru") ? "🔙 Главное меню" : "🔙 主菜单"));

        sendMessageWithButtons(chatId, " ", buttons);
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
            String nativeLang = userOpt.get().getNativeLanguage();
            String message = nativeLang.equals("ru") ? "🔁 Ты ещё не отметил ни одного слова как 'не знаю'." : "🔁 你还没有标记任何单词为“不认识”。";
            sendMessage(chatId, message);
            showMainMenu(chatId);
            return;
        }

        String nativeLang = userOpt.get().getNativeLanguage();
        String myWordsTitle = nativeLang.equals("ru") ? "🔁 *Твои слова (Не знаю)*\n\n" : "🔁 *你的单词 (不认识)*\n\n";

        StringBuilder sb = new StringBuilder();
        sb.append(myWordsTitle);

        List<List<String>> buttons = new ArrayList<>();
        List<String> row = new ArrayList<>();
        Map<String, Long> deleteMap = new HashMap<>();

        for (int i = 0; i < Math.min(userWords.size(), 20); i++) {
            UserWord uw = userWords.get(i);
            sb.append((i+1)).append(". ").append(uw.getWord().getWord()).append(" - ").append(uw.getWord().getTranslation()).append("\n");

            String buttonText = nativeLang.equals("ru") ? "❌ " : "❌ ";
            buttonText += uw.getWord().getWord();
            deleteMap.put(buttonText, uw.getWord().getId());
            row.add(buttonText);

            if (row.size() == 2) {
                buttons.add(new ArrayList<>(row));
                row.clear();
            }
        }

        if (!row.isEmpty()) {
            buttons.add(row);
        }

        String backButtonText = nativeLang.equals("ru") ? "⬅️ Назад в меню" : "⬅️ 返回菜单";
        buttons.add(List.of(backButtonText));

        userWordDeleteMap.put(chatId, deleteMap);

        sendMessageWithButtons(chatId, sb.toString(), buttons);
        userStates.put(chatId, ConversationState.IN_MY_WORDS);
    }

    private void handleMyWordsCommand(Long chatId, String command) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");

        String backButtonText = nativeLang.equals("ru") ? "⬅️ Назад в меню" : "⬅️ 返回菜单";

        if (command.equals(backButtonText)) {
            userStates.put(chatId, ConversationState.IN_MENU);
            showMainMenu(chatId);
            userWordDeleteMap.remove(chatId);
            return;
        } else if (command.startsWith(nativeLang.equals("ru") ? "❌ " : "❌ ")) {
            handleDeleteWord(chatId, command);
            return;
        } else {
            String instruction = nativeLang.equals("ru") ? "Для взаимодействия с 'Моими словами' используй кнопки." : "要与“我的单词”互动，请使用按钮。";
            sendMessage(chatId, instruction);
            showMyWords(chatId);
        }
    }

    private void handleDeleteWord(Long chatId, String buttonCommand) {
        Map<String, Long> deleteMap = userWordDeleteMap.get(chatId);
        if (deleteMap == null || !deleteMap.containsKey(buttonCommand)) {
            Optional<User> userOpt = userRepository.findByChatId(chatId);
            String nativeLang = userOpt.map(User::getNativeLanguage).orElse("ru");
            String errorMessage = nativeLang.equals("ru") ? "❌ Ошибка при удалении слова." : "❌ 删除单词时出错。";
            sendMessage(chatId, errorMessage);
            showMyWords(chatId);
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
            Optional<Word> wordOpt = wordRepository.findById(wordIdToDelete);
            String wordStr = wordOpt.map(Word::getWord).orElse("слово");
            String nativeLang = userOpt.get().getNativeLanguage();
            String successMessage;
            if (nativeLang.equals("ru")) {
                successMessage = "✅ Слово *" + wordStr + "* удалено из твоего списка.";
            } else {
                successMessage = "✅ 单词 *" + wordStr + "* 已从你的列表中删除。";
            }
            sendMessage(chatId, successMessage);
        } else {
            String nativeLang = userOpt.get().getNativeLanguage();
            String notFoundMessage = nativeLang.equals("ru") ? "❌ Слово не найдено в твоем списке." : "❌ 你的列表中找不到该单词。";
            sendMessage(chatId, notFoundMessage);
        }

        showMyWords(chatId);
    }

    private void showSettings(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String nativeLang = user.getNativeLanguage();

            String settingsText;
            List<List<String>> settingsButtons;
            
            if ("ru".equals(nativeLang)) {
                settingsText = "⚙️ *Твои настройки:*\n" +
                            "Родной язык: " + ("ru".equals(user.getNativeLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                            "Изучаемый язык: " + ("ru".equals(user.getTargetLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                            "Уровень: " + user.getLevel() + "\n\n" +
                            "Хочешь изменить что-нибудь?";
                
                settingsButtons = List.of(
                    List.of("🔄 Изменить родной язык"),
                    List.of("🔄 Изменить изучаемый язык"),
                    List.of("🔄 Изменить уровень"),
                    List.of("⬅️ Назад в меню")
                );
            } else {
                settingsText = "⚙️ *你的设置:*\n" +
                            "母语: " + ("ru".equals(user.getNativeLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                            "学习语言: " + ("ru".equals(user.getTargetLanguage()) ? "🇷🇺 Русский" : "🇨🇳 中文") + "\n" +
                            "级别: " + user.getLevel() + "\n\n" +
                            "想要改变什么吗？";
                
                settingsButtons = List.of(
                    List.of("🔄 改变母语"),
                    List.of("🔄 改变学习语言"),
                    List.of("🔄 改变级别"),
                    List.of("⬅️ 返回菜单")
                );
            }

            sendMessageWithButtons(chatId, settingsText, settingsButtons);
            userStates.put(chatId, ConversationState.IN_SETTINGS); 
            
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private enum ConversationState {
        START, AWAITING_NATIVE_LANG, AWAITING_TARGET_LANG, AWAITING_LEVEL,
        IN_MENU, IN_MY_WORDS, IN_SENTENCE_GAME, IN_SETTINGS, IN_DICTIONARY,
        AWAITING_NEW_NATIVE_LANG, AWAITING_NEW_TARGET_LANG, AWAITING_NEW_LEVEL
    }

    private void handleSettingsCommand(Long chatId, String command) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
            return;
        }
        
        User user = userOpt.get();
        String nativeLang = user.getNativeLanguage();

        String changeNativeCmdRu = "🔄 Изменить родной язык";
        String changeTargetCmdRu = "🔄 Изменить изучаемый язык";
        String changeLevelCmdRu = "🔄 Изменить уровень";
        String backCmdRu = "⬅️ Назад в меню";
        
        String changeNativeCmdZh = "🔄 改变母语";
        String changeTargetCmdZh = "🔄 改变学习语言";
        String changeLevelCmdZh = "🔄 改变级别";
        String backCmdZh = "⬅️ 返回菜单";

        switch (command) {
            case "🔄 Изменить родной язык", "🔄 改变母语" -> { 
                String text = "ru".equals(nativeLang) ? "Выбери свой новый родной язык:" : "选择你的新母语：";
                List<List<String>> languageButtons = List.of(List.of("🇷🇺 Русский", "🇨🇳 中文"));
                sendMessageWithButtons(chatId, text, languageButtons);
                userStates.put(chatId, ConversationState.AWAITING_NEW_NATIVE_LANG);
            }
            case "🔄 Изменить изучаемый язык", "🔄 改变学习语言" -> {
                handleNewTargetLanguageRequest(chatId); 
            }
            case "🔄 Изменить уровень", "🔄 改变级别" -> {
                String text = "ru".equals(nativeLang) ? "Выбери новый уровень знаний:" : "选择你的新级别：";
                List<List<String>> levelButtons = List.of(
                    List.of("A1", "A2"),
                    List.of("B1", "B2"),
                    List.of("C1", "C2")
                );
                sendMessageWithButtons(chatId, text, levelButtons);
                userStates.put(chatId, ConversationState.AWAITING_NEW_LEVEL);
            }
            case "⬅️ Назад в меню", "⬅️ 返回菜单" -> {
                showMainMenu(chatId);
                userStates.put(chatId, ConversationState.IN_MENU);
            }
            default -> {
                String errorMessage = "ru".equals(nativeLang) ? 
                    "Неизвестная команда. Пожалуйста, используй меню настроек." : 
                    "未知命令。请使用设置菜单。";
                sendMessage(chatId, errorMessage);
                showSettings(chatId);
            }
        }
    }

    private void handleNewTargetLanguageRequest(Long chatId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String nativeLang = user.getNativeLanguage();
            String text = "ru".equals(nativeLang) ? "Выбери новый язык для изучения:" : "选择你要学习的新语言：";
            List<List<String>> targetLangButtons;
            if ("ru".equals(nativeLang)) {
                targetLangButtons = List.of(List.of("🇨🇳 中文"));
            } else {
                targetLangButtons = List.of(List.of("🇷🇺 Русский"));
            }
            sendMessageWithButtons(chatId, text, targetLangButtons);
            userStates.put(chatId, ConversationState.AWAITING_NEW_TARGET_LANG);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private void handleNewNativeLanguageSelection(Long chatId, String selectedLanguage) {
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

            String confirmationText = "✅ Родной язык успешно изменён на *" +
                    ("ru".equals(nativeLangCode) ? "🇷🇺 Русский" : "🇨🇳 中文") + "*";
            sendMessage(chatId, confirmationText);

            String newTargetLang = "ru".equals(nativeLangCode) ? "zh" : "ru";
            user.setTargetLanguage(newTargetLang);
            userRepository.save(user);
            
            String autoChangeText = "🔄 Изучаемый язык автоматически изменён на *" +
                    ("ru".equals(newTargetLang) ? "🇷🇺 Русский" : "🇨🇳 中文") + "*";
            sendMessage(chatId, autoChangeText);
            
            showSettings(chatId);
            userStates.put(chatId, ConversationState.IN_SETTINGS);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private void handleNewTargetLanguageSelection(Long chatId, String selectedLanguage) {
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

            String confirmationText = "✅ Изучаемый язык успешно изменён на *" +
                    ("ru".equals(targetLangCode) ? "🇷🇺 Русский" : "🇨🇳 中文") + "*";
            sendMessage(chatId, confirmationText);
            showSettings(chatId);
            userStates.put(chatId, ConversationState.IN_SETTINGS);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

    private void handleNewLevelSelection(Long chatId, String selectedLevel) {
        if (!List.of("A1", "A2", "B1", "B2", "C1", "C2").contains(selectedLevel)) {
            sendMessage(chatId, "Пожалуйста, выбери уровень из предложенных вариантов.");
            return;
        }

        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setLevel(selectedLevel);
            userRepository.save(user);

            String confirmationText = "✅ Уровень знаний успешно изменён на *" + selectedLevel + "*";
            sendMessage(chatId, confirmationText);
            showSettings(chatId);
            userStates.put(chatId, ConversationState.IN_SETTINGS);
        } else {
            sendMessage(chatId, "Ошибка: пользователь не найден.");
            showMainMenu(chatId);
        }
    }

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

    private static class FlashcardGameSession {
        private final Long userId;
        private final String gameType;
        private final List<Word> words;
        private int currentIndex;

        public FlashcardGameSession(Long userId, String gameType, List<Word> words, int currentIndex) {
            this.userId = userId;
            this.gameType = gameType;
            this.words = new ArrayList<>(words);
            this.currentIndex = currentIndex;
        }

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
            this.words = new ArrayList<>(words);
            this.correctSentence = correctSentence;
        }

        public Long getUserId() { return userId; }
        public List<Word> getWords() { return words; }
        public String getCorrectSentence() { return correctSentence; }
    }
}