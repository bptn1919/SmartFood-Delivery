package com.amomeal.marketplace.profile.entity;

import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Mirrors ../../backend/profile/models.py::ChefPaymentInfo — bank details a
 * chef must provide to receive payouts. {@code bankName} is kept as a plain
 * {@code String} (see {@link VietnamBank}'s javadoc for why); validation of
 * every field (bank code format, account number format, account name format,
 * citizen id, tax code) is ported 1:1 from
 * ../../backend/profile/schemas/chef_payment.py's Pydantic field_validators in
 * {@link com.amomeal.marketplace.profile.service.ChefPaymentService}.
 */
@Entity
@Table(name = "chef_payment_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChefPaymentInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private CustomUser user;

    @Column(name = "bank_name", nullable = false, length = 255)
    private String bankName;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    @Column(name = "bank_account_number", nullable = false, length = 50)
    private String bankAccountNumber;

    @Column(name = "bank_account_name", nullable = false, length = 255)
    private String bankAccountName;

    @Column(name = "bank_branch", length = 255)
    private String bankBranch;

    @Column(name = "citizen_id", length = 20)
    private String citizenId;

    @Column(name = "tax_code", length = 20)
    private String taxCode;

    @Builder.Default
    @Column(name = "is_verified", nullable = false)
    private boolean isVerified = false;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
