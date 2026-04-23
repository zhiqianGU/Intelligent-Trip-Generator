package thesis.project.gu.mapper;

import thesis.project.gu.dto.UserRefreshToken;

import java.time.LocalDateTime;

public interface UserRefreshTokenMapper {
    int insert(UserRefreshToken token);
    UserRefreshToken findActiveByTokenHash(String tokenHash);
    int revokeById(Long id, LocalDateTime revokedAt);
    int revokeAllByUserId(Long userId, LocalDateTime revokedAt);
    int updateReplacedByTokenId(Long id, Long replacedByTokenId);
}
