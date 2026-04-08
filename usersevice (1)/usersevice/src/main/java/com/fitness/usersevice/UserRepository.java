package com.fitness.usersevice;

import com.fitness.usersevice.models.User;
import org.springframework.data.jpa.repository.JpaRepository;



public interface UserRepository extends JpaRepository<User, String> {
    Boolean existsByEmail(String email);
}
