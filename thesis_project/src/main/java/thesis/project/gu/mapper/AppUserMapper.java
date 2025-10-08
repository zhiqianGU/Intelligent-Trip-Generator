package thesis.project.gu.mapper;
import org.apache.ibatis.annotations.Param;
import thesis.project.gu.model.AppUser;

public interface AppUserMapper {
    void insert(AppUser u); // useGeneratedKeys
    AppUser findById(@Param("id") Long id);
    AppUser findByDisplayName(@Param("name") String name);
    boolean existsByDisplayName(@Param("name") String name);
}
