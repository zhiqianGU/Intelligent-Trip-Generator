package thesis.project.gu.model;
import lombok.Data;

import java.time.Instant;

@Data
public class UserCredential {
    private Long userId;
    private String password;
    private String passwordAlgo; // 'bcrypt'
    private java.time.Instant updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordAlgo() {
        return passwordAlgo;
    }

    public void setPasswordAlgo(String passwordAlgo) {
        this.passwordAlgo = passwordAlgo;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }


}
