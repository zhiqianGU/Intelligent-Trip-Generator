package thesis.project.gu.req;

import java.util.List;

public record CreatePlanReq(
        String city,
        int days,
        Integer budget,
        Party party,
        List<String> style,
        String pace,
        String mainModel,
        String departureDate
) {
    public record Party(Integer adults, Integer kids) {}
}
