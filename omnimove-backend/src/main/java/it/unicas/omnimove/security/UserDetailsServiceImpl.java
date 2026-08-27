package it.unicas.omnimove.security;
import it.unicas.omnimove.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {
    /** Unmatchable by design: no BCrypt hash ever equals this string. */
    private static final String NO_PASSWORD = "{noop}\u0000no-password";

    private final UserRepository userRepo;
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepo.findByEmail(email)
            .map(u -> User.withUsername(u.getEmail())
                // Spring's UserDetails refuses a null password, and a Google-only
                // account has none. The placeholder is never compared against
                // anything: password login reads users.password directly and
                // rejects an account without one, so this cannot become a way in.
                .password(u.hasPassword() ? u.getPassword() : NO_PASSWORD)
                .authorities(u.getRole())
                .build())
            .orElseThrow(()->new UsernameNotFoundException("User not found"));
    }
}
