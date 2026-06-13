package thesis.project.gu.planhistory.domain;




public class TripDay {
    Long id; Long planId; Integer dayIndex; Long hotelPlaceId;
    String hotelReason;
    String hotelTip;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Long getHotelPlaceId() {
        return hotelPlaceId;
    }

    public void setHotelPlaceId(Long hotelPlaceId) {
        this.hotelPlaceId = hotelPlaceId;
    }

    public Integer getDayIndex() {
        return dayIndex;
    }

    public void setDayIndex(Integer dayIndex) {
        this.dayIndex = dayIndex;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHotelReason() {
        return hotelReason;
    }

    public void setHotelReason(String hotelReason) {
        this.hotelReason = hotelReason;
    }

    public String getHotelTip() {
        return hotelTip;
    }

    public void setHotelTip(String hotelTip) {
        this.hotelTip = hotelTip;
    }

    String note;
}

