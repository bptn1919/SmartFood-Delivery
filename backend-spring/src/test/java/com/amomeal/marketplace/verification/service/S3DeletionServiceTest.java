package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.verification.entity.ScheduledS3Deletion;
import com.amomeal.marketplace.verification.repository.ScheduledS3DeletionRepository;
import com.amomeal.marketplace.verification.service.S3DeletionService.RunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class S3DeletionServiceTest {

    @Mock private S3ObjectDeleter s3;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private ScheduledS3DeletionRepository scheduledRepository;

    private S3DeletionService service;

    @BeforeEach
    void setUp() {
        service = new S3DeletionService(s3, attachmentRepository, scheduledRepository);
        when(s3.isEnabled()).thenReturn(true);
    }

    private Attachment attachment() {
        return Attachment.builder().uid(UUID.randomUUID()).bucket("bkt").directory("cccd").hashedName("h.jpg").build();
    }

    private ScheduledS3Deletion due(UUID attachmentUid) {
        ScheduledS3Deletion r = new ScheduledS3Deletion();
        r.setAttachmentUid(attachmentUid);
        r.setS3Bucket("bkt");
        r.setS3Key("cccd/h.jpg");
        r.setDeleteAfter(Instant.now().minusSeconds(5));
        return r;
    }

    @Test
    void deleteNow_removesObjectAndFlagsAttachment() {
        Attachment a = attachment();
        service.deleteNow(a);
        verify(s3).delete("bkt", "cccd/h.jpg");
        assertThat(a.isFileDeleted()).isTrue();
        assertThat(a.isDeleted()).isTrue();
        verify(attachmentRepository).save(a);
    }

    @Test
    void deleteNow_s3Disabled_orNull_isNoOp() {
        when(s3.isEnabled()).thenReturn(false);
        Attachment a = attachment();
        service.deleteNow(a);
        service.deleteNow(null);
        verify(s3, never()).delete(anyString(), anyString());
        assertThat(a.isFileDeleted()).isFalse();
    }

    @Test
    void deleteNow_failureRaises_andAttachmentNotFlagged() {
        Attachment a = attachment();
        doThrow(new IllegalStateException("denied")).when(s3).delete(anyString(), anyString());
        assertThatThrownBy(() -> service.deleteNow(a)).isInstanceOf(IllegalStateException.class);
        assertThat(a.isFileDeleted()).isFalse();
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void schedule_createsOneRowThirtyDaysOut_onlyOncePerAttachment() {
        Attachment a = attachment();
        when(scheduledRepository.existsByAttachmentUid(a.getUid())).thenReturn(false);
        service.schedule(a, 30);

        ArgumentCaptor<ScheduledS3Deletion> cap = ArgumentCaptor.forClass(ScheduledS3Deletion.class);
        verify(scheduledRepository).save(cap.capture());
        ScheduledS3Deletion row = cap.getValue();
        assertThat(row.getAttachmentUid()).isEqualTo(a.getUid());
        assertThat(row.getS3Bucket()).isEqualTo("bkt");
        assertThat(row.getS3Key()).isEqualTo("cccd/h.jpg");
        assertThat(Duration.between(Instant.now(), row.getDeleteAfter()))
                .isBetween(Duration.ofDays(30).minusSeconds(10), Duration.ofDays(30).plusSeconds(10));

        when(scheduledRepository.existsByAttachmentUid(a.getUid())).thenReturn(true);
        service.schedule(a, 30);
        verify(scheduledRepository, times(1)).save(any());
    }

    @Test
    void schedule_skipsNullAlreadyDeletedAndDisabled() {
        service.schedule(null, 30);
        Attachment gone = attachment();
        gone.setFileDeleted(true);
        service.schedule(gone, 30);
        when(s3.isEnabled()).thenReturn(false);
        service.schedule(attachment(), 30);
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void executePending_deletesDueRows_marksAttachmentAndRecord_countsFailures() {
        Attachment a = attachment();
        ScheduledS3Deletion ok = due(a.getUid());
        ScheduledS3Deletion bad = due(UUID.randomUUID());
        bad.setS3Key("cccd/bad.jpg");
        when(scheduledRepository.findByIsExecutedFalseAndDeleteAfterLessThanEqual(any())).thenReturn(List.of(ok, bad));
        when(attachmentRepository.findById(a.getUid())).thenReturn(Optional.of(a));
        doThrow(new IllegalStateException("boom")).when(s3).delete("bkt", "cccd/bad.jpg");

        RunResult r = service.executePending(false);

        assertThat(r).isEqualTo(new RunResult(2, 1, 1));
        assertThat(ok.isExecuted()).isTrue();
        assertThat(ok.getExecutedAt()).isNotNull();
        assertThat(a.isFileDeleted()).isTrue();
        assertThat(a.isDeleted()).isTrue();
        assertThat(bad.isExecuted()).isFalse(); // retried next run
    }

    @Test
    void executePending_dryRun_changesNothing() {
        ScheduledS3Deletion row = due(UUID.randomUUID());
        when(scheduledRepository.findByIsExecutedFalseAndDeleteAfterLessThanEqual(any())).thenReturn(List.of(row));
        RunResult r = service.executePending(true);
        assertThat(r).isEqualTo(new RunResult(1, 0, 0));
        verify(s3, never()).delete(anyString(), anyString());
        assertThat(row.isExecuted()).isFalse();
    }

    @Test
    void executePending_nothingDue() {
        when(scheduledRepository.findByIsExecutedFalseAndDeleteAfterLessThanEqual(any())).thenReturn(List.of());
        assertThat(service.executePending(false)).isEqualTo(new RunResult(0, 0, 0));
    }

    @Test
    void executePending_s3Disabled_marksExecutedWithoutDeleting_unlessDryRun() {
        when(s3.isEnabled()).thenReturn(false);
        ScheduledS3Deletion row = due(UUID.randomUUID());
        when(scheduledRepository.findByIsExecutedFalseAndDeleteAfterLessThanEqual(any())).thenReturn(List.of(row));

        service.executePending(true);
        assertThat(row.isExecuted()).isFalse();

        service.executePending(false);
        assertThat(row.isExecuted()).isTrue();
        verify(s3, never()).delete(anyString(), anyString());
        verify(scheduledRepository).saveAll(List.of(row));
    }

    @Test
    void executePending_attachmentMarkFailure_doesNotStopTheRecord() {
        Attachment a = attachment();
        ScheduledS3Deletion row = due(a.getUid());
        when(scheduledRepository.findByIsExecutedFalseAndDeleteAfterLessThanEqual(any())).thenReturn(List.of(row));
        when(attachmentRepository.findById(a.getUid())).thenThrow(new IllegalStateException("db"));
        assertThat(service.executePending(false)).isEqualTo(new RunResult(1, 1, 0));
        assertThat(row.isExecuted()).isTrue();
    }
}
