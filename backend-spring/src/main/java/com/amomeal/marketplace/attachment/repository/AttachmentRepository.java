package com.amomeal.marketplace.attachment.repository;

import com.amomeal.marketplace.attachment.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Mirrors ../../backend/attachment/queries.py::Query. */
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** Mirrors Query.get_instance_by_uid (uid + is_deleted=False + is_file_deleted=False). */
    Optional<Attachment> findByUidAndIsDeletedFalseAndIsFileDeletedFalse(UUID uid);
}
