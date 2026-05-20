package com.xrail.queue.dto;

import jakarta.validation.constraints.NotBlank;

public record QueueEnterRequest(@NotBlank String scope) {}
