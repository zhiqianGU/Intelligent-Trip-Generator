package thesis.project.gu.mapper;
import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.UserCredential;

public interface UserCredentialMapper {
    void insert(UserCredential c);
    UserCredential findByUserId(@Param("userId") Long userId);
    int updatePassword(@Param("userId") Long userId, @Param("password") String password, @Param("passwordAlgo") String passwordAlgo);
}
