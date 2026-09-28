package com.amomeal.marketplace.dish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Mirrors ../../backend/dish/schemas/responses.py::CandidateSchema. */
public record CandidateResponse(
        UUID uid,
        String name,
        double score,
        @JsonProperty("is_best") boolean best
) {
}
