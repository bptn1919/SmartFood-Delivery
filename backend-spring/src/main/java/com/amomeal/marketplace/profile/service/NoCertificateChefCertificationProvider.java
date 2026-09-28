package com.amomeal.marketplace.profile.service;

import org.springframework.stereotype.Component;

/** Default {@link ChefCertificationProvider} until `certificate` is ported — see that interface's javadoc. */
@Component
public class NoCertificateChefCertificationProvider implements ChefCertificationProvider {

    @Override
    public boolean isFoodSafetyCertified(Long chefUserId) {
        return false;
    }
}
