package com.amomeal.marketplace.order.service;

/**
 * Port of ../../backend/order/services/shipping_service.py::AhamoveAdapter's one
 * method, {@code estimate_fee}. An interface only so tests can substitute it;
 * production uses {@link AhamoveShippingFeeEstimator}.
 */
public interface ShippingFeeEstimator {

    /**
     * @return the quoted delivery fee in VND, or the hard-coded 15000 fallback
     *         whenever the provider errors/times out (Django returns the same
     *         number on a non-200 response AND on any {@code RequestException})
     */
    int estimateFee(Double pickupLat, Double pickupLng, Double dropoffLat, Double dropoffLng);
}
