package thesis.project.gu.user.security;

import org.springframework.stereotype.Service;
import thesis.project.gu.exception.ErrorCode;
import thesis.project.gu.exception.NavigatorException;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class CaptchaChallengeService {

    private static final long CHALLENGE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentMap<String, ChallengeEntry> challenges = new ConcurrentHashMap<>();

    public Challenge create() {
        cleanupExpired();
        int left = 2 + RANDOM.nextInt(8);
        int right = 2 + RANDOM.nextInt(8);
        String id = UUID.randomUUID().toString();
        String answer = String.valueOf(left + right);
        challenges.put(id, new ChallengeEntry(answer, System.currentTimeMillis() + CHALLENGE_TTL_MILLIS));
        return new Challenge(id, left + " + " + right + " = ?");
    }

    public void verifyRequired(String challengeId, String challengeAnswer) {
        if (challengeId == null || challengeId.isBlank() || challengeAnswer == null || challengeAnswer.isBlank()) {
            throw new NavigatorException(ErrorCode.CAPTCHA_REQUIRED, "Please complete the challenge verification");
        }
        ChallengeEntry entry = challenges.remove(challengeId.trim());
        if (entry == null || entry.expiresAtMillis < System.currentTimeMillis()) {
            throw new NavigatorException(ErrorCode.CAPTCHA_INVALID, "Challenge expired. Please try again.");
        }
        String expected = entry.answer.trim().toLowerCase(Locale.ROOT);
        String actual = challengeAnswer.trim().toLowerCase(Locale.ROOT);
        if (!expected.equals(actual)) {
            throw new NavigatorException(ErrorCode.CAPTCHA_INVALID, "Challenge answer is incorrect");
        }
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < now);
    }

    public record Challenge(String challengeId, String question) {
    }

    private record ChallengeEntry(String answer, long expiresAtMillis) {
    }
}
