package com.amomeal.marketplace.review.service;

import com.amomeal.marketplace.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Real implementation of {@link ReviewStatsProvider}, backed by {@link ReviewRepository}. */
@Component
@RequiredArgsConstructor
public class ReviewStatsProviderImpl implements ReviewStatsProvider {

    private final ReviewRepository reviewRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> reviewCountByDish(Collection<UUID> dishUids) {
        return reviewRepository.reviewCountMap(dishUids);
    }

    @Override
    @Transactional(readOnly = true)
    public double systemAverageRating() {
        Double avg = reviewRepository.systemAverageRatingRaw();
        return avg == null ? 0.0 : avg;
    }
}
