package thesis.project.gu.user.persistence;

import org.apache.ibatis.annotations.Param;
import thesis.project.gu.user.domain.UserIdentifier;

import java.util.List;

public interface UserIdentifierMapper {
    void insert(UserIdentifier identifier);

    UserIdentifier findByTypeAndIdentifier(@Param("type") String type, @Param("identifier") String identifier);

    UserIdentifier findByIdentifierAny(@Param("identifier") String identifier);

    List<UserIdentifier> findByUserId(@Param("userId") Long userId);
}
