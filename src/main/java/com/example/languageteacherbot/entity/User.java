// src/main/java/com/example/languageteacherbot/entity/User.java
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
    private Long chatId;

    private String firstName;
    private String lastName;
    private String nativeLanguage;
    private String targetLanguage;
    private String level;
    private LocalDateTime registeredAt;
    private LocalDateTime lastActivityAt;
}