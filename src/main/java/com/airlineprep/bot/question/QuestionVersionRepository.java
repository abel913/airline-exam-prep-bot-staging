package com.airlineprep.bot.question;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
public interface QuestionVersionRepository extends JpaRepository<QuestionVersion,Long> {
 Page<QuestionVersion> findByQuestionIdOrderByVersionNumberDesc(Long id, Pageable page);
 Optional<QuestionVersion> findFirstByContentExamTypeIdAndFingerprintOrderByIdAsc(Long exam, String hash);
 Optional<QuestionVersion> findFirstByContentExamTypeIdAndStemFingerprintOrderByIdAsc(Long exam, String hash);
 long countByContentCategoryId(Long categoryId);
}
