package com.amomeal.marketplace.verification.repository;

import com.amomeal.marketplace.verification.entity.ScheduledS3Deletion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScheduledS3DeletionRepository extends JpaRepository<ScheduledS3Deletion, Long> {

    boolean existsByAttachmentUid(UUID attachmentUid);

    List<ScheduledS3Deletion> findByIsExecutedFalseAndDeleteAfterLessThanEqual(Instant now);

    List<ScheduledS3Deletion> findByAttachmentUid(UUID attachmentUid);
}
