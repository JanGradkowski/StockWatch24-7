package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CustomerUserDetailService implements UserDetailsService {
    private final UserRepository userRepository;
    private final boolean verificationRequired;
    public CustomerUserDetailService(UserRepository userRepository,
                                     @Value("${security.email-verification.required:true}") boolean verificationRequired) {
        this.userRepository = userRepository;
        this.verificationRequired = verificationRequired;
    }
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow( ()
        -> new UsernameNotFoundException("User not found"));
        return new org.example.stockwatch247.security.AccountPrincipal(user,
                (!verificationRequired || user.isVerified()) && user.getDeletionRequestedAt() == null);
    }
}
