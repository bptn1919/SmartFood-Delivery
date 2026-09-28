package com.amomeal.marketplace.attachment.service;

import com.amomeal.marketplace.attachment.dto.GeneratePresignedUrlRequest;
import com.amomeal.marketplace.attachment.dto.GeneratePresignedUrlResponse;
import com.amomeal.marketplace.attachment.entity.Attachment;
import com.amomeal.marketplace.attachment.entity.AttachmentType;
import com.amomeal.marketplace.attachment.exception.AttachmentAlreadyCompletedException;
import com.amomeal.marketplace.attachment.exception.AttachmentIsNotCompletedException;
import com.amomeal.marketplace.attachment.exception.AttachmentNotFoundException;
import com.amomeal.marketplace.attachment.exception.InvalidUploadTokenException;
import com.amomeal.marketplace.attachment.repository.AttachmentRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the business logic in AttachmentService (mirrors
 * ../../backend/attachment/services.py), with the storage backend mocked out —
 * see AttachmentControllerTest (local filesystem backend) and
 * AttachmentS3ControllerTest (real S3-API backend, via LocalStack) for the
 * full-stack, real-Postgres happy-path coverage of both.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock
    private AttachmentRepository attachmentRepository;
    @Mock
    private CustomUserRepository customUserRepository;
    @Mock
    private AttachmentStorageService storageService;

    @InjectMocks
    private AttachmentService attachmentService;

    private CustomUser owner;

    @BeforeEach
    void setUp() {
        owner = CustomUser.builder().id(1L).username("chef").email("chef@amomeal.test").password("x").build();
    }

    @Test
    void generatePresignedUrl_createsAttachmentAndReturnsUploadUrl() {
        when(customUserRepository.getReferenceById(1L)).thenReturn(owner);
        when(storageService.bucketName()).thenReturn("amomeal-test-bucket");
        when(storageService.buildPublicUrl(any())).thenReturn("http://localhost:8000/media/dish/abc.png");
        when(storageService.generateUploadUrl(any())).thenAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            a.setUploadToken("tok123");
            return "http://localhost:8000/api/attachments/" + a.getUid() + "/upload?token=tok123";
        });
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest("food.png", 1024, AttachmentType.DISH);
        GeneratePresignedUrlResponse response = attachmentService.generatePresignedUrl(1L, request);

        assertThat(response.uid()).isNotNull();
        assertThat(response.url()).contains("/upload?token=tok123");

        verify(attachmentRepository).save(argThat(a ->
                a.getType() == AttachmentType.DISH
                        && a.getDirectory().equals("dish")
                        && a.getOriginalName().equals("food.png")
                        && a.getOwner() == owner
                        && !a.isCompleted()
        ));
    }

    @Test
    void completedUpload_marksCompletedWhenFileExists() {
        Attachment attachment = baseAttachment();
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(attachment.getUid()))
                .thenReturn(Optional.of(attachment));
        when(storageService.fileExists(attachment)).thenReturn(true);
        when(customUserRepository.getReferenceById(1L)).thenReturn(owner);
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean result = attachmentService.completedUpload(1L, attachment.getUid(), null);

        assertThat(result).isTrue();
        assertThat(attachment.isCompleted()).isTrue();
        assertThat(attachment.getUpdater()).isEqualTo(owner);
    }

    @Test
    void completedUpload_throwsWhenAttachmentMissing() {
        UUID uid = UUID.randomUUID();
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(uid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> attachmentService.completedUpload(1L, uid, null))
                .isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    void completedUpload_throwsWhenAlreadyCompleted() {
        Attachment attachment = baseAttachment();
        attachment.setCompleted(true);
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(attachment.getUid()))
                .thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> attachmentService.completedUpload(1L, attachment.getUid(), null))
                .isInstanceOf(AttachmentAlreadyCompletedException.class);
    }

    @Test
    void completedUpload_throwsWhenFileNotYetUploaded() {
        Attachment attachment = baseAttachment();
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(attachment.getUid()))
                .thenReturn(Optional.of(attachment));
        when(storageService.fileExists(attachment)).thenReturn(false);

        assertThatThrownBy(() -> attachmentService.completedUpload(1L, attachment.getUid(), null))
                .isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    void handleAttachment_returnsAttachmentWhenCompleted() {
        Attachment attachment = baseAttachment();
        attachment.setCompleted(true);
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(attachment.getUid()))
                .thenReturn(Optional.of(attachment));

        Attachment result = attachmentService.handleAttachment(attachment.getUid());

        assertThat(result).isSameAs(attachment);
    }

    @Test
    void handleAttachment_throwsWhenNotCompleted() {
        Attachment attachment = baseAttachment();
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(attachment.getUid()))
                .thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> attachmentService.handleAttachment(attachment.getUid()))
                .isInstanceOf(AttachmentIsNotCompletedException.class);
    }

    @Test
    void handleAttachment_throwsWhenMissing() {
        UUID uid = UUID.randomUUID();
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(uid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> attachmentService.handleAttachment(uid))
                .isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    void storeUploadedFile_rejectsWrongToken() {
        Attachment attachment = baseAttachment();
        attachment.setUploadToken("correct-token");
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(attachment.getUid()))
                .thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> attachmentService.storeUploadedFile(
                attachment.getUid(), "wrong-token", new ByteArrayInputStream(new byte[0]), 0L))
                .isInstanceOf(InvalidUploadTokenException.class);
    }

    @Test
    void storeUploadedFile_storesWhenTokenMatches() throws Exception {
        Attachment attachment = baseAttachment();
        attachment.setUploadToken("correct-token");
        when(attachmentRepository.findByUidAndIsDeletedFalseAndIsFileDeletedFalse(attachment.getUid()))
                .thenReturn(Optional.of(attachment));

        attachmentService.storeUploadedFile(attachment.getUid(), "correct-token", new ByteArrayInputStream(new byte[0]), 0L);

        verify(storageService).storeFile(eq(attachment), any(), eq(0L));
    }

    private Attachment baseAttachment() {
        return Attachment.builder()
                .uid(UUID.randomUUID())
                .type(AttachmentType.DISH)
                .originalName("food.png")
                .hashedName("hashed.png")
                .size(1024)
                .contentType("image/png")
                .bucket("local")
                .directory("dish")
                .isPublic(true)
                .publicUrl("http://localhost:8000/media/dish/hashed.png")
                .isCompleted(false)
                .build();
    }
}
