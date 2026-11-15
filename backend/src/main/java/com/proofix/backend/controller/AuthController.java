package com.proofix.backend.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.proofix.backend.dto.UserLoginRequest;
import com.proofix.backend.dto.UserLoginResponse;
import com.proofix.backend.dto.UserRegistrationRequest;
import com.proofix.backend.dto.UserRegistrationResponse;
import com.proofix.backend.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserRegistrationResponse> register(
            @Valid @RequestBody UserRegistrationRequest request) {

        UserRegistrationResponse response =
                userService.registerUser(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
public ResponseEntity<UserLoginResponse> login(
        @Valid @RequestBody UserLoginRequest request) {

    UserLoginResponse response = userService.loginUser(request);

    return ResponseEntity.ok(response);
}
    @GetMapping("/me")
public ResponseEntity<Map<String, String>> getCurrentUser(
        Authentication authentication) {

    String userId = authentication.getName();

    return ResponseEntity.ok(
            Map.of("userId", userId)
    );
}
}