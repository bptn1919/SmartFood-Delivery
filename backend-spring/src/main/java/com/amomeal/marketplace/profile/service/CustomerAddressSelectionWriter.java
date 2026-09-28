package com.amomeal.marketplace.profile.service;

import com.amomeal.marketplace.profile.entity.CustomerAddress;
import com.amomeal.marketplace.profile.repository.CustomerAddressRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Mirrors ../../backend/profile/orm/profile.py::CustomerORM.set_default_customer_address's
 * {@code with transaction.atomic():} block, which COMMITS before
 * {@code CustomerService.set_default_customer_address} decides whether to
 * raise {@code ProfileDoesNotExist} — i.e. even an invalid {@code address_id}
 * still deselects every one of the user's addresses in Django, because that
 * write's atomic block is a separate, already-committed unit from the
 * exception raised afterward in the caller.
 *
 * <p>Per CLAUDE.md §8b, replicating that in Spring requires the write to be
 * its OWN {@code REQUIRES_NEW} transaction in a separate bean (a
 * {@code @Transactional} method called from within the same class is
 * self-invocation and never passes through the proxy) — otherwise
 * {@link CustomerService#setDefaultCustomerAddress} throwing after the write
 * would roll the whole thing back, which is the opposite of Django's actual
 * behavior here.
 */
@Component
@RequiredArgsConstructor
public class CustomerAddressSelectionWriter {

    private final CustomerAddressRepository customerAddressRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applySelection(CustomUser user, Long addressId) {
        List<CustomerAddress> all = customerAddressRepository.findAllByUser(user);
        boolean matched = false;
        for (CustomerAddress a : all) {
            boolean isTarget = a.getId().equals(addressId);
            a.setSelected(isTarget);
            matched = matched || isTarget;
        }
        customerAddressRepository.saveAll(all);
        return matched;
    }
}
