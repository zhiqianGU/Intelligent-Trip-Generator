package thesis.project.gu.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    VALIDATION_ERROR("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, "Validation failed"),
    PARAM_ERROR("PARAM_ERROR", HttpStatus.BAD_REQUEST, "Invalid request parameter"),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),

    LOGIN_FAIL("LOGIN_FAIL", HttpStatus.UNAUTHORIZED, "Invalid account or password"),
    TOKEN_EXPIRED("TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED, "Login expired"),
    UNAUTHORIZED("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Login required"),
    TOO_MANY_LOGIN_ATTEMPTS("TOO_MANY_LOGIN_ATTEMPTS", HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts"),
    CAPTCHA_REQUIRED("CAPTCHA_REQUIRED", HttpStatus.BAD_REQUEST, "Challenge verification required"),
    CAPTCHA_INVALID("CAPTCHA_INVALID", HttpStatus.BAD_REQUEST, "Challenge verification failed"),

    USER_EXISTS("USER_EXISTS", HttpStatus.CONFLICT, "User already exists"),
    NOT_FOUND("NOT_FOUND", HttpStatus.NOT_FOUND, "Resource not found"),
    LIST_ITEM_EXISTS("LIST_ITEM_EXISTS", HttpStatus.CONFLICT, "List item already exists"),

    ROUTE_FAIL("ROUTE_FAIL", HttpStatus.UNPROCESSABLE_ENTITY, "No feasible route found"),
    GEOCODE_FAIL("GEOCODE_FAIL", HttpStatus.UNPROCESSABLE_ENTITY, "Geocoding failed");

    private final String code;
    private final HttpStatus status;
    private final String defaultMsg;

    ErrorCode(String code, HttpStatus status, String defaultMsg) {
        this.code = code;
        this.status = status;
        this.defaultMsg = defaultMsg;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMsg() {
        return defaultMsg;
    }

    public NavigatorException ex() {
        return new NavigatorException(this);
    }

    public NavigatorException ex(String customMsg) {
        return new NavigatorException(this, customMsg);
    }
}
