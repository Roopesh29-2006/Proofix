package com.proofix.backend.repository;


import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.proofix.backend.entity.UserCredential;

public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID>{

    Optional<UserCredential> findByUser_Email(String email);



}  
