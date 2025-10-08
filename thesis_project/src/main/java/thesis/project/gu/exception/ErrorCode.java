package thesis.project.gu.exception;

import org.springframework.http.HttpStatus;
import lombok.Getter;
@Getter
public enum ErrorCode {

    // ======= 通用 1xx =======
    VALIDATION_ERROR("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, "参数校验失败"),
    INTERNAL_ERROR   ("INTERNAL_ERROR",   HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),

    // ======= 鉴权 2xx =======
    LOGIN_FAIL       ("LOGIN_FAIL",       HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    TOKEN_EXPIRED    ("TOKEN_EXPIRED",    HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"),

    // ======= 业务 3xx =======
    ROUTE_FAIL("ROUTE_FAIL", HttpStatus.UNPROCESSABLE_ENTITY, "未找到可行路线"),
    GEOCODE_FAIL("GEOCODE_FAIL", HttpStatus.UNPROCESSABLE_ENTITY, "地理编码失败"),
    USER_EXISTS("USER_EXISTS", HttpStatus.UNPROCESSABLE_ENTITY, "用户名/邮箱/手机号 已被使用"),
    PARAM_ERROR("PARAM_ERROR",HttpStatus.UNPROCESSABLE_ENTITY , "login 必须是有效的邮箱或手机号"),
    NOT_FOUND("NOT_FOUND",HttpStatus.UNPROCESSABLE_ENTITY , "无此表" ),
    LIST_ITEM_EXISTS("LIST_ITEM_EXISTS",HttpStatus.UNPROCESSABLE_ENTITY ,"该地点已在列表中，无需重复添加" );

    private final String code;
    private final HttpStatus status;
    private final String defaultMsg;

    public String getDefaultMsg() {
        return defaultMsg;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }



    ErrorCode(String code, HttpStatus status, String defaultMsg) {
        this.code = code;
        this.status = status;
        this.defaultMsg = defaultMsg;
    }
    public NavigatorException ex() {
        return new NavigatorException(this);
    }
    public NavigatorException ex(String customMsg) {
        return new NavigatorException(this, customMsg);
    }

}
