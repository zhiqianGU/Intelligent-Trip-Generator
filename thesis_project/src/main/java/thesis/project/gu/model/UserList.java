package thesis.project.gu.model;

import java.time.LocalDateTime;
import java.util.Date;

public class UserList {
    private Long id;
    private Long userId;
    private String listname;
    private Boolean isPublic;
    private Boolean state;
    private String note;
    private LocalDateTime created_at;
    private LocalDateTime updated_at;

    public UserList(Long id, Long userId, Boolean isPublic, String listname, Boolean state, String note, LocalDateTime created_at, LocalDateTime updated_at) {
        this.id = id;
        this.userId = userId;
        this.isPublic = isPublic;
        this.listname = listname;
        this.state = state;
        this.note = note;
        this.created_at = created_at;
        this.updated_at = updated_at;
    }



    public Boolean getState() {
        return state;
    }

    public void setState(Boolean state) {
        this.state = state;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }



    public String getListname() {
        return listname;
    }

    public void setListname(String listname) {
        this.listname = listname;
    }
    public UserList() {

    }


    public Boolean getPublic() {
        return isPublic;
    }

    public void setPublic(Boolean aPublic) {
        isPublic = aPublic;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }


    public LocalDateTime getCreated_at() {
        return created_at;
    }

    public void setCreated_at(LocalDateTime created_at) {
        this.created_at = created_at;
    }

    public LocalDateTime getUpdated_at() {
        return updated_at;
    }

    public void setUpdated_at(LocalDateTime updated_at) {
        this.updated_at = updated_at;
    }


}
