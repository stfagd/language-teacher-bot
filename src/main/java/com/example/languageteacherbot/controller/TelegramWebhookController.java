// src/main/java/com/example/languageteacherbot/controller/TelegramWebhookController.java
package com.example.languageteacherbot.controller;

import com.example.languageteacherbot.service.TelegramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/update")
public class TelegramWebhookController {

    @Autowired
    private TelegramService telegramService;

    @PostMapping
    public ResponseEntity<String> handleUpdate(@RequestBody Map<String, Object> update) {
        telegramService.processUpdate(update);
        return ResponseEntity.ok().build();
    }
}