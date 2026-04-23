package thesis.project.gu.mapper;

import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.UserIdentifier;

import java.util.List;

public interface UserIdentifierMapper {
    void insert(UserIdentifier identifier);

    UserIdentifier findByTypeAndIdentifier(@Param("type") String type, @Param("identifier") String identifier);

    UserIdentifier findByIdentifierAny(@Param("identifier") String identifier);

    List<UserIdentifier> findByUserId(@Param("userId") Long userId);
}
