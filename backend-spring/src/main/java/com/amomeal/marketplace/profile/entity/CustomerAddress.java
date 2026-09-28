package com.amomeal.marketplace.profile.entity;

import com.amomeal.marketplace.users.entity.CustomUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mirrors ../../backend/profile/models.py::CustomerAddress. Plain model (no
 * BaseModel / uid / timestamps in Django), so this entity uses a generated
 * {@code Long id} like every other field on it there.
 *
 * <p>PORT-NOTE (real Django quirk, preserved): none of
 * {@code CustomerORM}'s read paths for this model (list, "get one",
 * "get one by id") filter {@code deleted=False} — only
 * {@code soft_delete_customer_address} ever sets the flag. So "soft delete"
 * here does not actually hide the address from the customer's own listing/
 * lookup endpoints; it only flips a flag nothing reads. Ported faithfully in
 * {@code CustomerAddressRepository} (no repository method filters
 * {@code deleted}), not "fixed" into an actually-hidden soft delete.
 */
@Entity
@Table(name = "customer_address")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private CustomUser user;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false, length = 255)
    private String street;

    @Column(nullable = false, length = 100)
    private String ward;

    @Column(nullable = false, length = 100)
    private String district;

    @Column(nullable = false, length = 100)
    private String city;

    private Double latitude;

    private Double longitude;

    @Builder.Default
    @Column(nullable = false)
    private boolean selected = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    public String fullAddress() {
        return address + ", " + street + ", " + ward + ", " + district + ", " + city;
    }
}
