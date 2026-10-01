package com.airlineprep.bot.question;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ImportRowRepository extends JpaRepository<ImportRow,Long> {
 Page<ImportRow> findByBatchIdOrderByRowNumber(Long id, Pageable page);
 List<ImportRow> findByBatchIdOrderByRowNumber(Long id);
 Page<ImportRow> findByBatchIdAndQuestionIdIsNotNullOrderByRowNumber(Long id,Pageable page);
 long deleteByBatchId(Long id);
 @Modifying @Query(value="DELETE FROM question_import_rows WHERE batch_id=:id AND question_id IS NULL AND (errors<>'' OR duplicate_status<>'UNIQUE')",nativeQuery=true)
 int deleteInvalidStagingRows(@Param("id") Long id);
}
