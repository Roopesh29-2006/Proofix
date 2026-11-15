package com.proofix.backend.dto;

import java.util.UUID;

public class UserLoginResponse {
    private UUID userId;
    private String firstName;
    private String lastName;
    private String email;
    private String accessToken;
    private String tokenType;

    public UserLoginResponse(UUID userId, String firstName, String lastName, String email, String accessToken) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.accessToken = accessToken;
        this.tokenType = "Bearer";
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

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }


    
}
