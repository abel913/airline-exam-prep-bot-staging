package com.airlineprep.bot.question;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ImportBatchRepository extends JpaRepository<ImportBatch,Long> {
 org.springframework.data.domain.Page<ImportBatch> findByStatusIn(java.util.Collection<ImportStatus> statuses,org.springframework.data.domain.Pageable page);
}
