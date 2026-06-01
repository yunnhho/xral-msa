package com.xrail.train.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReservationRequest(
        @NotNull Long scheduleId,
        @NotNull Long departureStationId,
        @NotNull Long arrivalStationId,
        @NotEmpty List<Long> seatIds
) {}
