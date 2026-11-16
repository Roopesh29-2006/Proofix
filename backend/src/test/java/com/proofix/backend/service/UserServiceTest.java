package com.proofix.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proofix.backend.dto.UserRegistrationRequest;
import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.repository.UserCredentialRepository;
import com.proofix.backend.repository.UserRepository;
import com.proofix.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserCredentialRepository credentialRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private UserService userService;

    @Test
    void duplicateEmailIsConflict() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setFirstName("Ada");
        request.setLastName("Lovelace");
        request.setEmail("  Ada@Example.com ");
        request.setPassword("correct-horse");
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(true);

        ConflictException failure = assertThrows(ConflictException.class,
                () -> userService.registerUser(request));

        assertEquals("An account with this email already exists",
                failure.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }
}
