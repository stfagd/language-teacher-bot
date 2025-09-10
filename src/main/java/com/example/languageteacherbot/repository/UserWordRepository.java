package com.example.languageteacherbot.repository;

import com.example.languageteacherbot.entity.UserWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserWordRepository extends JpaRepository<UserWord, Long> {
    List<UserWord> findByUserChatId(Long userChatId);
    Optional<UserWord> findByUserChatIdAndWordId(Long userChatId, Long wordId);
    void deleteByUserChatIdAndWordId(Long userChatId, Long wordId);
}