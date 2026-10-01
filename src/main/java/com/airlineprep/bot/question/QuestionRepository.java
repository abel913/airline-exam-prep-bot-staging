package com.airlineprep.bot.question;
import org.springframework.data.jpa.repository.*;
public interface QuestionRepository extends JpaRepository<Question,Long>, JpaSpecificationExecutor<Question> {
 @Override @EntityGraph(attributePaths="currentVersion")
 org.springframework.data.domain.Page<Question> findAll(org.springframework.data.jpa.domain.Specification<Question> spec, org.springframework.data.domain.Pageable page);
 long countByStatus(QuestionStatus status);
 @org.springframework.data.jpa.repository.Query("select q.id from Question q where q.status=:status and q.id>:after order by q.id")
 java.util.List<Long> findIdsAfter(@org.springframework.data.repository.query.Param("status") QuestionStatus status,
  @org.springframework.data.repository.query.Param("after") Long after,org.springframework.data.domain.Pageable page);
}
