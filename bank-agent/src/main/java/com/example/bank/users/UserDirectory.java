package com.example.bank.users;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo customer directory. The bank app has no login; the browser UI picks one of
 * these seeded customers and every request carries the userId so accounts,
 * transactions, conversations and documents stay scoped per customer.
 */
@Service
public class UserDirectory {

    private static final Map<String, BankUser> USERS = Map.of(
            "alice", new BankUser("alice", "Alice Anderson", "retail", "Berlin Mitte"),
            "bob", new BankUser("bob", "Bob Brown", "premium", "Munich South"),
            "carol", new BankUser("carol", "Carol Clark", "business", "Hamburg Harbor"));

    public List<BankUser> list() {
        return List.copyOf(USERS.values());
    }

    public Optional<BankUser> find(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(USERS.get(userId.trim().toLowerCase()));
    }

    public BankUser resolve(String userId) {
        return find(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown userId '" + userId + "'. Known users: " + String.join(", ", USERS.keySet())));
    }
}
