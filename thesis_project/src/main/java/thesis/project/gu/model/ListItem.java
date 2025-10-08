package thesis.project.gu.model;

public class ListItem {
    private Long id;
    private Long listId;
    private Long placeId;
    private String note;

    public ListItem(Long id, Long listId, Long placeId, String note) {
        this.id = id;
        this.listId = listId;
        this.placeId = placeId;
        this.note = note;
    }

    public ListItem() {

    }


    public Long getPlaceId() {
        return placeId;
    }

    public void setPlaceId(Long placeId) {
        this.placeId = placeId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getListId() {
        return listId;
    }

    public void setListId(Long listId) {
        this.listId = listId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }


}
