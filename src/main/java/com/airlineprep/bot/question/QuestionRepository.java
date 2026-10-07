package com.airlineprep.bot.question;
import org.springframework.data.jpa.repository.*;
public interface QuestionRepository extends JpaRepository<Question,Long>, JpaSpecificationExecutor<Question> {
 @Override @EntityGraph(attributePaths="currentVersion")
 org.springframework.data.domain.Page<Question> findAll(org.springframework.data.jpa.domain.Specification<Question> spec, org.springframework.data.domain.Pageable page);
 long countByStatus(QuestionStatus status);
 @org.springframework.data.jpa.repository.Query(value="select count(*) from questions where status <> 'PUBLISHED'",nativeQuery=true)
 long countNonPublished();
 @org.springframework.data.jpa.repository.Query(value="select coalesce(max(id),0) from questions",nativeQuery=true)
 long maximumId();
 @org.springframework.data.jpa.repository.Query(value="select id from questions where status <> 'PUBLISHED' and id > :after and id <= :upperBound order by id",nativeQuery=true)
 java.util.List<Long> findNonPublishedIdsAfter(@org.springframework.data.repository.query.Param("after") Long after,
  @org.springframework.data.repository.query.Param("upperBound") Long upperBound,org.springframework.data.domain.Pageable page);
 @org.springframework.data.jpa.repository.Modifying(flushAutomatically=true)
 @org.springframework.data.jpa.repository.Query(value="delete from questions where id=:id and status <> 'PUBLISHED'",nativeQuery=true)
 int deleteNonPublishedById(@org.springframework.data.repository.query.Param("id") Long id);
 @org.springframework.data.jpa.repository.Query("select q.id from Question q where q.status=:status and q.id>:after order by q.id")
 java.util.List<Long> findIdsAfter(@org.springframework.data.repository.query.Param("status") QuestionStatus status,
  @org.springframework.data.repository.query.Param("after") Long after,org.springframework.data.domain.Pageable page);
}
