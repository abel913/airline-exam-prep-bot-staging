package com.airlineprep.bot.access;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessEntitlementRepository extends JpaRepository<AccessEntitlement, Long> {
    java.util.Optional<AccessEntitlement> findByUserId(Long userId);
    java.util.Optional<AccessEntitlement> findByUserIdAndExamTypeId(Long userId, Long examTypeId);
    java.util.List<AccessEntitlement> findAllByUserIdOrderByExamTypeId(Long userId);
}
