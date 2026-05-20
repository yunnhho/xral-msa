package com.xrail.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentRequest(
        @NotNull Long reservationId,
        @NotNull @Positive Long amount,
        @NotBlank String method
) {}
