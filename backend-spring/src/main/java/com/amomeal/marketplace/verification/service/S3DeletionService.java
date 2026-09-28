package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.verification.entity.ScheduledS3Deletion;
import com.amomeal.marketplace.verification.repository.ScheduledS3DeletionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sensitive-file removal, ports of: verification.py::{_delete_from_s3, _schedule_s3_deletion} and
 * the management command {@code delete_scheduled_s3}. The Django command is run daily by an
 * external cron ({@code 0 3 * * *}); the Spring equivalent is {@link #scheduledRun()} on the same
 * cron (no Celery/broker involved, CLAUDE.md section 6), disabled with
 * {@code app.verification.s3-deletion-enabled=false}. Everything is gated by
 * {@link S3ObjectDeleter#isEnabled()} (Django USE_S3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3DeletionService {

    private final S3ObjectDeleter s3;
    private final AttachmentRepository attachmentRepository;
    private final ScheduledS3DeletionRepository scheduledRepository;

    @Value("${app.verification.s3-deletion-enabled:true}")
    private boolean schedulerEnabled = true;

    public record RunResult(int found, int deleted, int failed) {
    }

    /**
     * Django _delete_from_s3: delete the physical file NOW and flag the Attachment
     * (is_file_deleted + is_deleted). Does nothing when S3 is disabled; a failed delete raises.
     */
    public void deleteNow(Attachment attachment) {
        if (attachment == null || !s3.isEnabled()) {
            return;
        }
        String key = attachment.getDirectory() + "/" + attachment.getHashedName();
        try {
            s3.delete(attachment.getBucket(), key);
            log.info("Deleted CCCD file from S3: {}", key);
        } catch (RuntimeException e) {
            log.error("S3 delete failed for attachment {}: {}", attachment.getUid(), e.getMessage());
            throw e;
        }
        attachment.setFileDeleted(true);
        attachment.setDeleted(true);
        attachmentRepository.save(attachment);
    }

    /** Django _schedule_s3_deletion: record a future deletion once per attachment (get_or_create). */
    public void schedule(Attachment attachment, int delayDays) {
        if (attachment == null || !s3.isEnabled()) {
            return;
        }
        if (attachment.isFileDeleted()) {
            return;
        }
        if (scheduledRepository.existsByAttachmentUid(attachment.getUid())) {
            return;
        }
        ScheduledS3Deletion row = new ScheduledS3Deletion();
        row.setAttachmentUid(attachment.getUid());
        row.setS3Bucket(attachment.getBucket());
        row.setS3Key(attachment.getDirectory() + "/" + attachment.getHashedName());
        row.setDeleteAfter(Instant.now().plus(Duration.ofDays(delayDays)));
        scheduledRepository.save(row);
        log.info("Scheduled S3 deletion for attachment {} in {} days", attachment.getUid(), delayDays);
    }

    @Scheduled(cron = "${app.verification.s3-deletion-cron:0 0 3 * * *}")
    void scheduledRun() {
        if (schedulerEnabled) {
            executePending(false);
        }
    }

    /** Django management command {@code delete_scheduled_s3} (with {@code --dry-run} = dryRun). */
    public RunResult executePending(boolean dryRun) {
        List<ScheduledS3Deletion> pending =
                scheduledRepository.findByIsExecutedFalseAndDeleteAfterLessThanEqual(Instant.now());
        if (pending.isEmpty()) {
            return new RunResult(0, 0, 0);
        }
        if (!s3.isEnabled()) {
            log.warn("S3 disabled - skipping {} scheduled deletion(s)", pending.size());
            if (!dryRun) {
                Instant now = Instant.now();
                pending.forEach(r -> {
                    r.setExecuted(true);
                    r.setExecutedAt(now);
                });
                scheduledRepository.saveAll(pending);
            }
            return new RunResult(pending.size(), 0, 0);
        }
        int deleted = 0;
        int failed = 0;
        for (ScheduledS3Deletion record : pending) {
            if (dryRun) {
                log.info("[DRY-RUN] Would delete: s3://{}/{}", record.getS3Bucket(), record.getS3Key());
                continue;
            }
            try {
                s3.delete(record.getS3Bucket(), record.getS3Key());
                try {
                    attachmentRepository.findById(record.getAttachmentUid()).ifPresent(a -> {
                        a.setFileDeleted(true);
                        a.setDeleted(true);
                        attachmentRepository.save(a);
                    });
                } catch (RuntimeException e) {
                    log.warn("Could not mark Attachment {} as deleted: {}", record.getAttachmentUid(), e.getMessage());
                }
                record.setExecuted(true);
                record.setExecutedAt(Instant.now());
                scheduledRepository.save(record);
                deleted++;
            } catch (RuntimeException e) {
                failed++;
                log.error("Failed to delete {}/{}: {}", record.getS3Bucket(), record.getS3Key(), e.getMessage());
            }
        }
        return new RunResult(pending.size(), deleted, failed);
    }
}
