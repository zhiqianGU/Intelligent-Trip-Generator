package thesis.project.gu.mapper;
import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.UserIdentifier;

public interface UserIdentifierMapper {
    void insert(UserIdentifier i);
    UserIdentifier findByTypeAndIdentifier(@Param("type") String type, @Param("identifier") String identifier);
    UserIdentifier findByIdentifierAny(@Param("identifier") String identifier); // 不管类型直接找
}