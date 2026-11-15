package com.proofix.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public class UserRegistrationResponse {

    private UUID userId;
    private String firstName;
    private String lastName;
    private String email;
    private OffsetDateTime createdAt;

    public UserRegistrationResponse(
            UUID userId,
            String firstName,
            String lastName,
            String email,
            OffsetDateTime createdAt) {

        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.createdAt = createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}