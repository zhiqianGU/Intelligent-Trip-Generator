package thesis.project.gu.user.persistence;

import thesis.project.gu.user.domain.UserRefreshToken;

import java.time.LocalDateTime;

public interface UserRefreshTokenMapper {
    int insert(UserRefreshToken token);
    UserRefreshToken findActiveByTokenHash(String tokenHash);
    int revokeById(Long id, LocalDateTime revokedAt);
    int revokeAllByUserId(Long userId, LocalDateTime revokedAt);
    int updateReplacedByTokenId(Long id, Long replacedByTokenId);
}
