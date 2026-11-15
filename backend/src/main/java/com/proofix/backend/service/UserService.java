
package com.proofix.backend.service;

import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.proofix.backend.dto.UserLoginRequest;
import com.proofix.backend.dto.UserLoginResponse;
import com.proofix.backend.dto.UserRegistrationRequest;
import com.proofix.backend.dto.UserRegistrationResponse;
import com.proofix.backend.entity.User;
import com.proofix.backend.entity.UserCredential;
import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.repository.UserCredentialRepository;
import com.proofix.backend.repository.UserRepository;
import com.proofix.backend.security.JwtService;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    // User Registration
    @Transactional
    public UserRegistrationResponse registerUser(
            UserRegistrationRequest request) {

        String normalizedEmail = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ConflictException(
                    "An account with this email already exists");
        }

        User user = new User();

        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName().trim());
        user.setEmail(normalizedEmail);

        User savedUser = userRepository.saveAndFlush(user);

        UserCredential credential = new UserCredential();

        credential.setUser(savedUser);
        credential.setPasswordHash(
                passwordEncoder.encode(request.getPassword()));

        credential.setPasswordChangedAt(OffsetDateTime.now());

        credentialRepository.save(credential);

        return new UserRegistrationResponse(
                savedUser.getUserId(),
                savedUser.getFirstName(),
                savedUser.getLastName(),
                savedUser.getEmail(),
                savedUser.getCreatedAt()
        );
    }

    // User Login
    @Transactional(readOnly = true)
    public UserLoginResponse loginUser(UserLoginRequest request) {

        String normalizedEmail = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        UserCredential credential = credentialRepository
                .findByUser_Email(normalizedEmail)
                .orElseThrow(() ->
                        new BadCredentialsException(
                                "Invalid email or password"));

        String storedHash = credential.getPasswordHash();

        if (storedHash == null ||
                !passwordEncoder.matches(
                        request.getPassword(), storedHash)) {

            throw new BadCredentialsException(
                    "Invalid email or password");
        }

        User user = credential.getUser();

        // Generate JWT after successful password verification
        String accessToken = jwtService.generateToken(
                user.getUserId(),
                user.getEmail()
        );

        return new UserLoginResponse(
                user.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                accessToken
        );
    }
}
