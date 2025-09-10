package com.example.languageteacherbot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    private Long chatId; // Используем chatId как первичный ключ

    private String firstName;
    private String lastName;
    private String nativeLanguage; // "ru" или "zh"
    private String targetLanguage; // "ru" или "zh"
    private String level; // "A1", "A2", ..., "C2"
    private LocalDateTime registeredAt;
    private LocalDateTime lastActivityAt;
}