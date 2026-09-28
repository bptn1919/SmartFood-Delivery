package com.amomeal.marketplace.verification.service;

import com.amomeal.marketplace.verification.entity.ChefVerificationSession;
import com.amomeal.marketplace.verification.exception.VerificationSessionNotFoundException;
import com.amomeal.marketplace.verification.repository.ChefVerificationSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Session persistence for {@link VerificationService}. Deliberately NOT transactional at this
 * level: every call is its own short transaction (the repository's), which reproduces Django's
 * autocommit semantics - CLAUDE.md section 8b "persist, then raise": e.g. a failed cross-validation
 * saves its error list and THEN raises, and {@code get_or_create} survives a later
 * AttachmentNotFound; a single outer transaction would roll both back.
 */
@Component
@RequiredArgsConstructor
public class VerificationSessionStore {

    private final ChefVerificationSessionRepository repository;

    /** Django ChefVerificationSession.objects.get_or_create(user=user). */
    public ChefVerificationSession getOrCreate(Long userId) {
        return repository.findByUserId(userId).orElseGet(() -> {
            ChefVerificationSession fresh = new ChefVerificationSession();
            fresh.setUserId(userId);
            try {
                return repository.saveAndFlush(fresh);
            } catch (DataIntegrityViolationException raced) {
                return repository.findByUserId(userId).orElseThrow();
            }
        });
    }

    /** Django _require_session. */
    public ChefVerificationSession require(Long userId) {
        return repository.findByUserId(userId).orElseThrow(VerificationSessionNotFoundException::new);
    }

    public ChefVerificationSession save(ChefVerificationSession session) {
        return repository.save(session);
    }
}
