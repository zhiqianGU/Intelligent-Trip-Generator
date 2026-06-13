package thesis.project.gu.user.persistence;
import org.apache.ibatis.annotations.Param;
import thesis.project.gu.user.domain.UserCredential;

public interface UserCredentialMapper {
    void insert(UserCredential c);
    UserCredential findByUserId(@Param("userId") Long userId);
    int updatePassword(@Param("userId") Long userId, @Param("password") String password, @Param("passwordAlgo") String passwordAlgo);
}
