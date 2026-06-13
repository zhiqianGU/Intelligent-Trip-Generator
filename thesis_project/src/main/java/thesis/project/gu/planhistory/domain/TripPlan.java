package thesis.project.gu.planhistory.domain;


public class TripPlan {
    Long id; Long userId; String title; String city; Integer days;
    Integer budgetCents; Integer partyAdults;
    String departureDate;

    public Boolean getFavorite() {
        return favorite;
    }

    public void setFavorite(Boolean favorite) {
        this.favorite = favorite;
    }

    Boolean favorite;

    public Integer getPartyKids() {
        return partyKids;
    }

    public void setPartyKids(Integer partyKids) {
        this.partyKids = partyKids;
    }

    public String getPace() {
        return pace;
    }

    public void setPace(String pace) {
        this.pace = pace;
    }

    public Integer getPartyAdults() {
        return partyAdults;
    }

    public void setPartyAdults(Integer partyAdults) {
        this.partyAdults = partyAdults;
    }

    public Integer getBudgetCents() {
        return budgetCents;
    }

    public void setBudgetCents(Integer budgetCents) {
        this.budgetCents = budgetCents;
    }

    public Integer getDays() {
        return days;
    }

    public void setDays(Integer days) {
        this.days = days;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDepartureDate() {
        return departureDate;
    }

    public void setDepartureDate(String departureDate) {
        this.departureDate = departureDate;
    }

    Integer partyKids; String pace;
}
