package com.xrail.train.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record ReservationRequest(
        @NotNull Long scheduleId,
        @NotEmpty List<Long> seatIds,
        @NotNull int startStationIdx,
        @NotNull int endStationIdx
) {}
