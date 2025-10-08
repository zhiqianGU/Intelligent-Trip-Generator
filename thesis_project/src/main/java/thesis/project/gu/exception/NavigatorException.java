package thesis.project.gu.exception;

import lombok.Getter;


// 统一业务异常：
//        - code  : 业务错误码，方便前端/监控定位
//        - message : 人类可读的描述





public class NavigatorException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 使用默认描述 */
    public NavigatorException(ErrorCode errorCode) {
        super(errorCode.getDefaultMsg());
        this.errorCode = errorCode;
    }

    /** 传入自定义描述 */
    public NavigatorException(ErrorCode errorCode, String customMsg) {
        super(customMsg);
        this.errorCode = errorCode;
    }

    public String getCode() {
        return errorCode.getCode();
    }
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
