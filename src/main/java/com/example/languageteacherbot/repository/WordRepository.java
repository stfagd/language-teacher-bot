// src/main/java/com/example/languageteacherbot/repository/WordRepository.java
package com.example.languageteacherbot.repository;

import com.example.languageteacherbot.entity.Word;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WordRepository extends JpaRepository<Word, Long> {
    List<Word> findByLevelAndLang(String level, String lang);
    Optional<Word> findByWordAndLang(String word, String lang);
}