package com.xrail.queue.dto;

import jakarta.validation.constraints.Pattern;

public record QueueModeRequest(
        @Pattern(regexp = "AUTO|FORCE_ON|FORCE_OFF", message = "mode는 AUTO/FORCE_ON/FORCE_OFF 중 하나여야 합니다.")
        String mode
) {}
