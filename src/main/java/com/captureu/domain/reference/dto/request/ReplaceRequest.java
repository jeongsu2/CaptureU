package com.captureu.domain.reference.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;

public record ReplaceRequest(@NotNull List<String> usernames) {
}
