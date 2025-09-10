package com.example.languageteacherbot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "words",
    uniqueConstraints = @UniqueConstraint(columnNames = {"word", "lang"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Word {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String word; // Слово на целевом языке

    @Column(nullable = false)
    private String translation; // Перевод на родной язык

    @Column(nullable = false)
    private String level; // "A1", "A2", ..., "C2"

    @Column(nullable = false)
    private String lang; // Язык слова ("ru" или "zh")
}